package com.softwaremill.macwire.dependencyLookup

import com.softwaremill.macwire.{Util, TypeCheckUtil, Debug}

import scala.annotation.tailrec
import scala.collection.immutable.TreeSet
import scala.reflect.macros.blackbox

private[dependencyLookup] class EligibleValuesFinder[C <: blackbox.Context](val c: C, debug: Debug) {

  import c.universe.{Scope => RScope, _}
  import EligibleValuesFinder._

  private val typeCheckUtil = new TypeCheckUtil[c.type](c, debug)
  import typeCheckUtil._

  def find(): EligibleValues = {
    def containsCurrentlyExpandedWireCall(t: Tree): Boolean = t.exists(_.pos == c.enclosingPosition)

    @tailrec
    def doFind(trees: List[(Scope, Tree)], values: EligibleValues): EligibleValues = trees match {
      case Nil => values
      case (scope, tree) :: tail => tree match {

        case _ if containsCurrentlyExpandedWireCall(tree) =>
          val (treesToAdd, newValues) = tree match {

            case block@Block(statements, expr) =>
              // the statements might contain vals, defs, or imports which will be
              // analyzed in the match clauses below (see `case ValDefOrDefDef`)
              (statements :+ expr, values)

            case ValDef(_, name, _, rhs) =>
              (List(rhs), values)

            case DefDef(_, name, _, curriedParams, tpt, rhs) =>
              debug.withBlock(s"Inspecting the parameters of method $name") {
                (List(rhs), values.putAll(Scope.Local, extractMatchingParams(curriedParams.flatten)))
              }

            case Function(params, body) =>
              debug.withBlock("Inspecting a function that contains the wire call") {
                (List(body), values.putAll(Scope.Local, extractMatchingParams(params)))
              }

            case ifBlock@If(cond, then, otherwise) =>
              (List(then, otherwise), values)

            case Match(_, cases) =>
              (cases, values)

            case CaseDef(_, _, body) =>
              (List(body), values)

            case _ =>
              (Nil, values)
          }
          // we're in a block that contains the wire call, therefore we're looking at the smallest scope, Local
          doFind(treesToAdd.map(Scope.Local -> _) ::: tail, newValues)

        case ValDefOrDefDef(name, tpt, rhs, symbol) if name.toString != "<init>" =>
          var newValues = values.put(scope, Ident(name), treeToCheck(tree, rhs)) // rhs might be empty for local def

          // it might be a @Module, let's see
          val hasSymbol = tpt.symbol != null // sometimes tpt has no symbol...
          val valIsModule = hasSymbol && hasModuleAnnotation(tpt.symbol)
          // the java @Inherited meta-annotation does not seem to be understood by scala-reflect...
          val valParentIsModule = hasSymbol && !valIsModule && typeCheckIfNeeded(tpt).baseClasses.exists(hasModuleAnnotation)

          if (valIsModule || valParentIsModule) {
            newValues = debug.withBlock(s"Inspecting module $tpt") {
              val moduleExprs: List[(Tree,Tree)] = typeCheckIfNeeded(tpt).members.filter(filterMember(_, ignoreImplicit = false)).map { member =>
                val tree = q"$name.$member"
                (tree,tree)
              }.toList
              // the module members are put in the wider scope
              newValues.putAll(scope.widen, moduleExprs)
            }
          }
          doFind(tail, newValues)

        case Import(expr, selectors) =>
          val newValues = debug.withBlock("Inspecting imports"){
            val importCandidates: List[(Symbol, Tree)] =
              (if (selectors.exists { selector => selector.name.toString == "_" }) {
                // wildcard import on `expr`
                typeCheckIfNeeded(expr).members.map {
                  s => s -> s.name.decodedName
                }
              } else {
                val selectorNames = selectors.map(s => s.name -> s.rename).toMap
                typeCheckIfNeeded(expr).
                  members.
                  collect { case m if selectorNames.contains(m.name) =>
                  m -> selectorNames(m.name)
                }
              }).map { case (s, name) => (s, Ident(name)) }.toList
            values.putAll(scope, filterImportMembers(importCandidates).map(t => (t,t)))
          }

          doFind(tail, newValues)

        case _ => 
          doFind(tail, values)
      }
    }

    debug.withBlock("Building eligible values") {
      registerParentsMembers(
        doFind(enclosingClassBody.map(Scope.Class -> _), EligibleValues.empty))
    }
  }
  
  /** @return all the members of all the parents */
  private def registerParentsMembers(values: EligibleValues): EligibleValues = {
    val parents = c.enclosingClass match {
      case ClassDef(_, _, _, Template(pp, self, _)) =>
        val selfTypes = self.tpt match {
          case ident : Ident => List(ident)
          case CompoundTypeTree(Template(selfParents,_,_)) => selfParents
          case _ => Nil
        }
        pp ++ selfTypes

      case ModuleDef(_, _, Template(pp, _, _)) => pp
      case e =>
        c.error(c.enclosingPosition, s"Unknown type of enclosing class: ${e.getClass}")
        Nil
    }

    parents.foldLeft(values) { case (newValues,parent) =>
      val tpe: Tree = parent match {
        case q"$tpe(..$params)" => tpe // ignore parameters passed to the parent
        case q"$tpe" => tpe
      }
      if (tpe.symbol.fullName == "scala.AnyRef") {
        newValues
      } else {
        debug.withBlock(s"Inspecting parent $tpe members") {
          typeCheckIfNeeded(tpe).members.
            filter(filterMember(_, ignoreImplicit = false)).
            foldLeft(newValues) { case (newValues, symbol) =>
            newValues.put(Scope.ParentOrModule, symbol.typeSignature,
              Ident(TermName(symbol.name.decodedName.toString.trim()))) // q"$symbol" crashes the compiler...
          }
        }
      }
    }
  }

  private def hasModuleAnnotation(symbol: Symbol) : Boolean = {
    symbol.annotations.exists { annotation =>
      annotation.tree match {
        case q"new $parent()" => parent.symbol.fullName == "com.softwaremill.macwire.Module"
        case _ => false
      }
    }
  }

  private def filterImportMembers[T](members: List[(Symbol,T)]) : List[T] = {
    members.collect { case (m,t) if filterMember(m, ignoreImplicit = true) => t }
  }

  private def filterMember(member: Symbol, ignoreImplicit: Boolean) : Boolean = {
    !member.fullName.startsWith("java.lang.Object") &&
    !member.fullName.startsWith("scala.Any") &&
    !member.fullName.endsWith("<init>") &&
    !member.fullName.endsWith("$init$") &&
    member.isPublic &&
    !(ignoreImplicit && member.isImplicit)
  }

  private def treeToCheck(tree: Tree, rhs: Tree) = {
    // If possible, we check the definition (rhs). We can't always check the tree, as it would cause recursive
    // type ascription needed errors from the compiler.
    if (rhs.isEmpty) tree else rhs
  }

  private def extractMatchingParams(params: List[ValDef]): List[(Tree,Tree)] = params.collect {
    case param@ValDef(_, name, tpt, _) => (Ident(name), treeToCheck(param, tpt))
  }

  case class EligibleValue(tpe: Type, expr: Tree)

  class EligibleValues(values: Map[Scope,Set[EligibleValue]]) {

    def putAll(scope: Scope, exprs: List[(Tree,Tree)]): EligibleValues = {
      exprs.foldLeft(this) {
        case (ev, (expr,tree)) => ev.put(scope, expr, tree)
      }
    }

    def put(scope: Scope, expr: Tree, tree: Tree): EligibleValues = {
      val tpe = typeCheckUtil.typeCheckIfNeeded(expr, tree)
      put(scope, tpe, expr)
    }
    
    def put(scope: Scope, tpe: Type, expr: Tree): EligibleValues = {
      debug(s"Found $expr of type $tpe in scope $scope")
      val set = values.getOrElse(scope, Set.empty) + EligibleValue(tpe, expr)
      new EligibleValues(values.updated(scope, set))
    }

    def findInFirstScope(tpe: Type, startingWith: Scope = Scope.Local): Set[Tree] = {
      @tailrec
      def forScope(scope: Scope) : Set[Tree] = {
        findInScope(tpe, scope) match {
          case set if set.isEmpty && scope < Scope.Max => forScope(scope.widen)
          case set if set.isEmpty => debug(s"Could not find $tpe in any scope"); Set.empty
          case exprs =>
            debug(s"Found [${exprs.mkString(", ")}] of type [$tpe] in scope $scope")
            exprs
        }
      }
      TreeSet.empty(Util.structuralTreeOrdering[c.type](c)) ++ forScope(startingWith)
    }

    def findInAllScope(tpe: Type): Set[Tree] = {
      @tailrec
      def accInScope(scope: Scope, acc: Set[Tree]): Set[Tree] = {
        val newAcc = findInScope(tpe, scope) ++ acc
        if( scope < Scope.Max ) accInScope(scope.widen, newAcc) else newAcc
      }
      TreeSet.empty(Util.structuralTreeOrdering[c.type](c)) ++ accInScope(Scope.Local, Set.empty)
    }

    def findInScope(tpe: Type, scope: Scope): Set[Tree] = {
      for( scopedValue <- values.getOrElse(scope, Set.empty) if checkCandidate(target = tpe, tpt = scopedValue.tpe)) yield {
        scopedValue.expr
      }
    }
  }

  object EligibleValues {
    val empty: EligibleValues = new EligibleValues(Map.empty)
  }

  object ValDefOrDefDef {
    def unapply(t: Tree): Option[(TermName, Tree, Tree, Symbol)] = t match {
      case ValDef(_, name, tpt, rhs) => Some((name, tpt, rhs, t.symbol))
      case DefDef(_, name, _, _, tpt, rhs) => Some((name, tpt, rhs, t.symbol))
      case _ => None
    }
  }

  /** @return Nil if no body can be found */
  private def enclosingClassBody: List[Tree] = c.enclosingClass match {
    case ClassDef(_, _, _, Template(_, _, body)) => body
    case ModuleDef(_, _, Template(_, _, body)) => body
    case e =>
      c.error(c.enclosingPosition, s"Unknown type of enclosing class: ${e.getClass}")
      Nil
  }
}

object EligibleValuesFinder {
  abstract class Scope private(val value: Int) extends Ordered[Scope] {
    /** @return the next Scope until Max */
    def widen: Scope

    /** @return the previous Scope, or Local if this is already the Local scope */
    def narrow: Scope

    override def compare(that: Scope): Int = this.value.compare(that.value)
    override def equals(other: Any): Boolean = other match {
      case otherScope: Scope => this.value == otherScope.value
      case _ => false
    }
    override def hashCode = value.hashCode
  }

  object Scope extends Ordering[Scope] {

    /** The smallest Scope */
    case object Local extends Scope(1) {
      def narrow: Scope = Local
      def widen: Scope = Class
    }
    case object Class extends Scope(2) {
      def narrow: Scope = Local
      def widen: Scope = ParentOrModule
    }
    case object ParentOrModule extends Scope(3) {
      def narrow: Scope = Class
      def widen: Scope = ParentOrModule
    }
    lazy val Max = ParentOrModule

    override def compare(a: Scope, b: Scope): Int = a.compare(b)
  }
}

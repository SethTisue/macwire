package com.softwaremill.macwire

import org.scalatest.{Matchers, FlatSpec}
import io.Source
import tools.reflect.ToolBoxError

class CompileTests extends FlatSpec with Matchers {

  val GlobalImports = "import com.softwaremill.macwire._\n\n"
  val DirectiveRegexp = "#include ([a-zA-Z]+)".r
  val EmptyResult = "\n\n()"

  def ambiguousResMsg(depClassName: String): String = s"Found multiple values of type [$depClassName]"

  def valueNotFound(depClassName: String): String = s"Cannot find a value of type: [$depClassName]"
  
  type CompilationResult = List[String]
  
  val success: CompilationResult = Nil
  
  def compileErr(messageParts: String*): CompilationResult = List.apply(messageParts: _*)

  val tests = List(
    ("simpleValsOkInTrait", success),
    ("simpleValsOkInObject", success),
    ("simpleValsOkInClass", success),
    ("simpleValsOkInClassParameters", success),
    ("simpleValsErrorMissingValue", compileErr(valueNotFound("B"))),
    ("simpleValsErrorDuplicateValue", compileErr(ambiguousResMsg("B"), "theB1", "theB2")),
    ("simpleDefsOkInTrait", success),
    ("simpleLazyValsOkInTrait", success),
    ("simpleWithAbstractOk", success),
    ("simpleValsReferenceWithAscriptionOk", success),
    ("simpleLazyValsNotInOrderOk", success),
    ("simpleValsMultipleParameterLists", success),
    ("simpleValsImplicitParameterLists", success),
    ("classesWithTraitsLazyValsOkInTrait", success),
    ("import", success),
    ("importAmbiguous", compileErr(ambiguousResMsg("A"), "myA", "theA")),
    ("importRename", success),
    ("importWildcard", success),
    ("importWildcardVisibility", success),
    ("importWildcardDoNotImportObjectMethods", success),
    ("inheritanceSimpleLazyValsOkInTraits", success),
    ("inheritanceSimpleDefsOkInTraits", success),
    ("inheritanceParametrized", success),
    ("inheritanceTwoLevelSimpleLazyValsOkInTraits", success),
    ("inheritanceDoubleSimpleLazyValsOkInTraits", success),
    ("inheritanceClassesWithTraitsLazyValsOkInTraits", success),
    ("simpleWithAbstractScopeOk", success),
    ("methodSingleParamOk", success),
    ("methodParamsOk", success),
    ("methodParamsInApplyOk", success),
    ("methodMixedOk", success),
    ("wiredSimple", success),
    ("wiredLazy", success),
    ("wiredPrimitive", success),
    ("wiredWithWire", success),
    ("wiredInherited", success),
    ("wiredDefs", success),
    ("wiredFromClass", success),
    ("wiredVisibility", success),
    ("wiredClassWithTypeParameters", success),
    // explicit param should not be resolved with implicit value when dependency cannot be found during plain, old regular lookup
    ("explicitDepsNotWiredWithImplicitVals", compileErr(valueNotFound("A"))),
    // non-implicit params should be resolved with implicit values if are in scope
    ("explicitDepsWiredWithImplicitValsFromMethodScope", compileErr(ambiguousResMsg("A"), "dependency", "implicitDependencyA")),
    ("explicitDepsWiredWithImplicitValsFromEnclosingModuleScope", success),
    ("explicitDepsWiredWithImplicitValsFromParentsScope", success),
    // implicit params should be resolved with implicit values or defs
    ("implicitDepsWiredWithImplementedImplicitVals", success),
    ("implicitDepsWiredWithImplicitDefs", success),
    ("implicitDepsWiredWithImplicitVals", success),
    ("implicitDepsWiredWithImplicitValsFromMethodScope", success),
    ("implicitDepsWiredWithImplicitValsFromEnclosingModuleScope", success),
    ("implicitDepsWiredWithImplicitValsFromParentsScope", success),
    // implicit params should be resolved with regular values
    // dependency resolution should abort compilation when there are ambiguous dependencies in scope
    ("diamondInheritance", success),
    ("selfType", success),
    ("taggedOk", success),
    ("taggedPrimitiveOk", success),
    ("taggedErrorNoValueWithTag", compileErr(valueNotFound("com.softwaremill.macwire.tagging.@@[Berry,Blue]"))),
    ("multipleMethodParametersFail", compileErr(ambiguousResMsg("A"), "a1", "a2")),
    ("anonFuncArgsWiredOk", success),
    ("anonFuncAndMethodsArgsWiredOk", success),
    ("nestedAnonFuncsWiredOk", success),
    ("nestedMethodsWiredOk", success),
    ("nestedMethodsWiredFail", compileErr(ambiguousResMsg("A"), "outerA", "innerA")),
    ("nestedWithManyMatchingParamsWiredFail", compileErr(ambiguousResMsg("A"), "a1", "a2", "a3")),
    ("methodWithWiredWithinIfThenElseOk", success),
    ("methodWithWiredWithinPatternMatchOk", success),
    ("methodWithSingleImplicitParamOk", success),
    ("methodWithTaggedParamsOk", success),
    ("methodWithTaggedParamsNotFoundFail", compileErr(valueNotFound("com.softwaremill.macwire.tagging.@@[Berry,Blue]"))),
    ("methodWithTaggedParamsAmbiguousFail", compileErr(ambiguousResMsg("com.softwaremill.macwire.tagging.@@[Berry,Blue]"), "blueberryArg1", "blueberryArg2")),
    ("wireSet", success),
    ("moduleWiring", success),
    ("moduleAnnotationWireInClass", success),
    ("moduleAnnotationWireInClassOverlappedByImport", success),
    ("moduleAnnotationFromParent", success),
    ("moduleAnnotationWireSet", success),
    ("moduleAnnotationWireInClassDoNotImportObjectMethods", success),
    ("moduleAnnotationWithAscription", success)
  )

  for ((testName, expectedErrors) <- tests)
    addTest(testName, expectedErrors)

  def addTest(testName: String, expectedResult: CompilationResult, imports: String = GlobalImports) {
    testName should (if (expectedResult == success) "compile & run" else "cause a compile error") in {
      import scala.reflect.runtime._
      val cm = universe.runtimeMirror(getClass.getClassLoader)

      import scala.tools.reflect.ToolBox
      val tb = cm.mkToolBox()

      val source = loadTest(testName, imports)

      try {
        tb.eval(tb.parse(source))
        if (expectedResult != success) {
          fail(s"Expected the following compile errors: $expectedResult")
        }
      } catch {
        case e: ToolBoxError => {
          if (expectedResult == success) {
            fail(s"Expected compilation & evaluation to be successful, but got an error: ${e.message}", e)
          } else {
            expectedResult.foreach(expectedError => e.message should include (expectedError))
          }
        }
      }
    }
  }

  def loadTest(name: String, imports: String) = imports + resolveDirectives(loadResource(name)) + EmptyResult

  def loadResource(name: String) = {
    val resource = this.getClass.getResourceAsStream("/" + name)
    if (resource == null) throw new IllegalArgumentException(s"Cannot find resource: $name")
    Source.fromInputStream(resource).getLines().mkString("\n")
  }

  def resolveDirectives(in: String): String = {
    DirectiveRegexp.findAllMatchIn(in).foldLeft(in)((acc, m) => {
      val includeName = m.group(1)
      val replacement = loadResource(includeName)
      acc.replaceAll("#include " + includeName + "(?!\\w)", replacement)
    })
  }
}

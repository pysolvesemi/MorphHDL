package spinal.core.internals

import spinal.core._

/** Definition projection for authenticated scalar values, selectors and Vec depths.
  * Instance witness records and their exact roots remain untouched.
  */
private[internals] object NativeScalarFormalSchema {
  def bindings(component: Component): Vector[ExternalTypedFormalBinding] = {
    val geometry = scala.collection.mutable.ArrayBuffer.empty[ElaborationIntegerParameter]
    component.dslBody.walkDeclarations {
      case value: BaseType => ParameterizedWidth.expressionOf(value).foreach(e => geometry ++= e.parameters)
      case _ =>
    }
    geometry ++= ParameterizedMemory.parametersOf(component)
    geometry ++= ParameterizedVec.parametersOf(component)
    def loopParameters(regions: Vector[ParameterizedStructure.StructuralRegion]): Unit =
      regions.foreach { region =>
        region match {
          case loop: ParameterizedStructure.StructuralFor => geometry ++= loop.count.parameters
          case _ =>
        }
        region.blocks.foreach(block => loopParameters(block.regions))
      }
    loopParameters(ParameterizedStructure.regionsOf(component))
    geometry ++= ParameterizedProcess.parametersOf(component)
    geometry ++= ParameterizedBlackBoxGenericRegistry.parametersOf(component)
    // A Vec depth is a definition-owned scalar formal even though it controls
    // retained geometry rather than a packed port. Authenticate the exact shape
    // expression; never infer this classification from a native lane count.
    val vecDepths = ParameterizedVec.retainedVectorsOf(component).flatMap { vector =>
      ParameterizedVec.shapeOf(vector).toVector.map(_.depth)
    }
    val declarations = ExternalFormalParameterRegistry.completeTypedBindingsOf(component)
    // Admit direct declaration formals here, like native packed-port formals.
    // Derived branch-owned Vec dimensions retain their existing owner-domain
    // path; inspecting them here would reopen their completed branch scope.
    val depthFormals = vecDepths.filter(expression => expression.parameters.size == 1 &&
      declarations.exists(entry => (entry.binding.formal eq expression.parameters.head) &&
        expression.verilog == entry.binding.formal.name)).flatMap { expression =>
      ElaborationWidthAuthority.requireAuthoritative(expression, "native Vec depth formal",
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-WIDTH-AUTHORITY-MISSING")
      expression.parameters
    }
    val values = ExternalParameterizedValueRegistry.valuesOf(component).flatMap(_._2.expression.parameters) ++
      TypedVecStaticSelect.entries(component).flatMap(_.index.expression.parameters) ++
      ParameterizedStructure.parametersOf(component) ++
      MorphHdlExternalParameterizedVerilog.forwardedParameters(component)
    declarations.filter { entry =>
      val formal = entry.binding.formal
      (values.exists(_ eq formal) && !geometry.exists(_.name == formal.name)) ||
        depthFormals.exists(_ eq formal)
    }
  }

  def definitionParameters(component: Component,
      parameters: Vector[ElaborationIntegerParameter]): Vector[ElaborationIntegerParameter] = {
    // A top-level component has no parent instance binding to restore its
    // supplied actual. Its public defaults must retain that invocation.
    if (component.parent == null) return parameters
    val scalar = bindings(component)
    NativeWidthFormalSchema.definitionParameters(component, parameters).map { parameter =>
      if (scalar.exists(_.binding.formal eq parameter)) parameter.copy(default = parameter.minimum)
      else parameter
    }
  }

  def validateGroup(candidates: Vector[Component]): Unit = {
    val schemas = candidates.map(component => bindings(component).map { entry =>
      val binding = entry.binding
      (binding.formal.name, binding.formal.minimum, binding.formal.maximum,
        binding.declarationKey, binding.ownerClassName)
    }.sortBy(_._1))
    if (schemas.distinct.size > 1)
      ParameterizedVerilogException.fail("SPINAL-PARAMETERIZED-VERILOG-FORMAL-SCHEMA-CONFLICT",
        "canonical scalar value formals must preserve declaration ownership, domain and structural use", None)
    // Native emission compares the complete captured bodies. Also retain their
    // structural selectors: the same witness bodies alone cannot establish
    // that two definitions choose those bodies under the same conditions.
    def selectors(regions: Vector[ParameterizedStructure.StructuralRegion]): Vector[String] =
      regions.flatMap { region =>
        val head = region match {
          case branch: ParameterizedStructure.StructuralIf =>
            Vector("if", branch.condition.verilog, branch.whenTrueLabel, branch.whenFalseLabel)
          case choice: ParameterizedStructure.StructuralCase =>
            Vector("case", choice.selector.verilog, choice.choices.map(_.value).mkString(","), choice.defaultLabel)
          case loop: ParameterizedStructure.StructuralFor => Vector("for", loop.count.verilog, loop.indexName, loop.label)
        }
        head ++ region.blocks.flatMap(block => Vector("{") ++ selectors(block.regions) ++ Vector("}"))
      }
    if (candidates.exists(c => bindings(c).nonEmpty || NativeWidthFormalSchema.bindings(c).nonEmpty) &&
        candidates.map(c => selectors(ParameterizedStructure.regionsOf(c))).distinct.size > 1)
      ParameterizedVerilogException.fail("SPINAL-PARAMETERIZED-VERILOG-FORMAL-STRUCTURE-CONFLICT",
        "canonical scalar formals must preserve structural selectors and branch layout", None)
  }
}

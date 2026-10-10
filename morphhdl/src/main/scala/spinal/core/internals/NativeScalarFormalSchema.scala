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
    // A nested scoped bound, index scale or expression width can be the only
    // use of a formal without a packed declaration. Its definition is still generic;
    // the complete typed binding retains each instance's actual independently.
    val scopedScalarUses = ElabScopedProcess.operations(component).flatMap(_.builder.geometry.flatMap(_.parameters))
      .filterNot(parameter => geometry.exists(_ eq parameter) ||
        ParameterizedBlackBoxGenericRegistry.parametersOf(component).exists(_ eq parameter))
    geometry ++= ParameterizedProcess.parametersOf(component)
    geometry ++= ParameterizedBlackBoxGenericRegistry.parametersOf(component)
    // A Vec depth is a definition-owned scalar formal even though it controls
    // retained geometry rather than a packed port. Authenticate the exact shape
    // expression; never infer this classification from a native lane count.
    // Resolve pruning and required publication before asking removed native
    // declarations for owner evidence. Keep nested and captured Vec identities
    // in the classification below; publication roots alone omit those shapes.
    ParameterizedVerilogVecs.publicationVectors(component)
    // Consumed reduction probes retain clone metadata, not native declarations.
    // Their private receipt authenticates the exact removed graph and rejects
    // changed owners, shapes, leaves and operations. Do not classify that dead
    // metadata as definition geometry; all other retained Vecs still require
    // their exact native declaration owners below.
    val vecDepths = ParameterizedVec.retainedVectorsOf(component)
      .filterNot(TypedBalancedReductionBackend.ownsConsumedProbe).flatMap { vector =>
      ParameterizedVec.shapeOf(vector).toVector.map(shape => vector -> shape.depth)
    }
    val declarations = ExternalFormalParameterRegistry.completeTypedBindingsOf(component)
    // Admit direct declaration formals here, like native packed-port formals.
    // Derived dimensions retain their existing owner-domain path; direct
    // formals may also be narrowed by a completed structural branch.
    val depthFormals = vecDepths.filter { case (_, expression) => expression.parameters.size == 1 &&
      declarations.exists(entry => (entry.binding.formal eq expression.parameters.head) &&
        expression.verilog == entry.binding.formal.name) }.flatMap { case (vector, expression) =>
      // Publication runs after structural capture has ended. Authenticate the
      // depth at every exact native leaf owner, including inactive branches,
      // rather than reopening its construction scope as a module-wide domain.
      val leaves = (vector: Data).flatten
      if (leaves.isEmpty)
        ParameterizedVerilogException.fail("SPINAL-PARAMETERIZED-VERILOG-FORMAL-WIDTH-AUTHORITY-MISSING",
          "native Vec depth formal has no exact native declaration owner", expression.sourceLocation)
      leaves.foreach { leaf =>
        NativePublicationWidth.validate(expression, component, leaf, "native Vec depth formal")
      }
      expression.parameters
    }
    val values = ExternalParameterizedValueRegistry.valuesOf(component).flatMap(_._2.expression.parameters) ++
      TypedVecStaticSelect.entries(component).flatMap(_.index.expression.parameters) ++
      ParameterizedStructure.parametersOf(component) ++
      MorphHdlExternalParameterizedVerilog.forwardedParameters(component)
    declarations.filter { entry =>
      val formal = entry.binding.formal
      (values.exists(_ eq formal) && !geometry.exists(_.name == formal.name)) ||
        depthFormals.exists(_ eq formal) || scopedScalarUses.exists(_ eq formal)
    }
  }

  def definitionParameters(component: Component,
      parameters: Vector[ElaborationIntegerParameter],
      canonicalSchema: Boolean = false): Vector[ElaborationIntegerParameter] = {
    // A top-level component has no parent instance binding to restore its
    // supplied actual. Its public defaults must retain that invocation.
    if (component.parent == null) return parameters
    val scalar = bindings(component)
    NativeWidthFormalSchema.definitionParameters(component, parameters, canonicalSchema).map { parameter =>
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

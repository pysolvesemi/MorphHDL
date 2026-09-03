package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.frontend.HdlInt
import morphhdl.ir.v1.{
  AttributeKind,
  BooleanParameter,
  CanonicalIrFacet,
  CanonicalIrHandoff,
  CanonicalIrProfile,
  DeclarationKind,
  DriverCoverage,
  DriverKind,
  IntegerParameter,
  IntExpr,
  NameOrigin,
  PackedValueSemantics,
  PortDirection,
  RtlExpr
}
import spinal.core._
import spinal.core.internals.{
  MorphHdlCanonicalIrProducer,
  MorphHdlCanonicalIrProducerException,
  PhaseCheckCrossClock,
  PhaseContext,
  PhaseMisc,
  PhaseNameNodesByReflection,
  PhaseNormalizeNodeInputs,
  PhasePropagateNames
}

private final class CanonicalIrNoPublicationPhase(
    target: Path,
    observed: AtomicBoolean
) extends PhaseMisc {
  override def impl(pc: PhaseContext): Unit = {
    assert(pc.topLevel != null)
    assert(!Files.exists(target), s"Verilog target already existed at canonical capture: $target")
    observed.set(true)
  }
}

final class MorphCanonicalIrHandoffTests extends AnyFunSuite {
  private def typedAlias(
      definitionName: String = "GenericTypedAlias",
      inputName: String = "payload_in",
      aliasName: String = "payload_alias",
      outputName: String = "payload_out",
      schemaCapture: Option[AtomicReference[ElaborationIntegerParameter]] = None
  ): Component = {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    new Component {
      setDefinitionName(definitionName)
      val input = in(Bits(width bits)).setName(inputName)
      val aliasValue = Bits(width bits).setName(aliasName).dontSimplifyIt()
      val output = out(Bits(width bits)).setName(outputName)
      val schema = ParameterizedWidth.parameterOf(input).getOrElse {
        fail("typed input did not retain its native elaboration schema")
      }
      assert(ParameterizedWidth.parameterOf(aliasValue).exists(_ eq schema))
      assert(ParameterizedWidth.parameterOf(output).exists(_ eq schema))
      schemaCapture.foreach(_.set(schema))
      aliasValue := input
      output := aliasValue
    }
  }

  test("typed single-source generation publishes one validated pre-emission handoff") {
    withTemporaryDirectory { directory =>
      val target = directory.resolve("canonical_alias.v")
      val observedBeforePublication = new AtomicBoolean(false)
      val retainedSchema = new AtomicReference[ElaborationIntegerParameter]()
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = target.getFileName.toString
      config.phasesInserters += { phases =>
        val boundary = phases.indexWhere(_.getClass == classOf[PhaseCheckCrossClock])
        assert(boundary >= 0)
        phases.insert(
          boundary + 1,
          new CanonicalIrNoPublicationPhase(target, observedBeforePublication)
        )
      }

      val report = MorphVerilog.generateWithCanonicalIr(config) {
        typedAlias(schemaCapture = Some(retainedSchema))
      }

      assert(observedBeforePublication.get())
      assert(Files.isRegularFile(target))
      assert(report.generation.generatedSourcesPaths == Vector(target.toString))
      assert(report.generation.elaborationParameters.map(_.name) == Vector("WIDTH"))
      val schema = retainedSchema.get()
      assert(schema ne null)
      assert(report.generation.elaborationParameters.head eq schema)
      assert(schema.default == 8)
      assert(schema.minimum == 1)
      assert(schema.maximum == 64)
      assert(report.handoff.profile == CanonicalIrProfile.SimpleWireAssignmentsV1)
      assert(report.handoff.completeFacets == CanonicalIrHandoff.productionFacets)
      assert(report.handoff.completeFacets.contains(CanonicalIrFacet.NameOrigins))

      val phases = report.phaseClassNames
      val crossClock = phases.indexWhere(_.endsWith(".PhaseCheckCrossClock"))
      val producer = phases.indexWhere(_.contains("MorphHdlCanonicalIrProducer$CapturePhase"))
      val propagation = phases.indexWhere(_.endsWith(".PhasePropagateNames"))
      val allocation = phases.indexWhere(_.endsWith(".PhaseAllocateNames"))
      val emission = phases.indexWhere(_.endsWith(".PhaseVerilog"))
      assert(crossClock >= 0)
      assert(producer == crossClock + 1)
      assert(propagation > producer)
      assert(allocation > propagation)
      assert(emission > allocation)
      assert(
        phases.exists(_.contains("ExternalSpinalVerilog$CapturePhase")),
        phases.mkString("\n")
      )

      val module = report.handoff.design.modules.head
      assert(module.parameters.size == 1)
      val width = module.parameters.head.asInstanceOf[morphhdl.ir.v1.IntegerParameter]
      assert(width.name == "WIDTH")
      assert(width.default == 8)
      assert(width.domain.minimum == 1)
      assert(width.domain.maximum == 64)
      assert(width.domain.admittedValues == (BigInt(1) to BigInt(64)).toVector)
      assert(module.declarations.size == 3)
      assert(module.drivers.size == 2)
      assert(module.drivers.forall(_.kind == DriverKind.Continuous))
      assert(module.drivers.forall(_.coverage == DriverCoverage.FullObject))
      assert(module.drivers.flatMap(_.value.referenceOccurrences).size == 2)
      assert(module.declarations.forall(
        _.packedType.exists(_.width == IntExpr.ParameterRef(width.id))
      ))
      assert(module.declarations.forall(
        _.packedType.exists(_.valueSemantics == PackedValueSemantics.BitVector)
      ))

      val origins = module.declarations.map(_.nameOrigin).collect {
        case NameOrigin.Explicit(value) => value
      }.toSet
      assert(origins == Set("payload_in", "payload_alias", "payload_out"))
      assert(module.declarations.count(_.kind == DeclarationKind.Port(PortDirection.Input)) == 1)
      assert(module.declarations.count(_.kind == DeclarationKind.Port(PortDirection.Output)) == 1)
      assert(module.declarations.count(_.kind == DeclarationKind.InternalCombinational) == 1)
    }
  }

  test("capture-disabled generation remains byte-identical and does not publish") {
    withTemporaryDirectory { directory =>
      val ordinaryDirectory = directory.resolve("ordinary")
      val canonicalDirectory = directory.resolve("canonical")
      Files.createDirectories(ordinaryDirectory)
      Files.createDirectories(canonicalDirectory)

      val ordinaryConfig = SpinalConfig(targetDirectory = ordinaryDirectory.toString)
      ordinaryConfig.netlistFileName = "alias.v"
      val ordinary = MorphVerilog(ordinaryConfig) { typedAlias() }

      val canonicalConfig = SpinalConfig(targetDirectory = canonicalDirectory.toString)
      canonicalConfig.netlistFileName = "alias.v"
      val canonical = MorphVerilog.generateWithCanonicalIr(canonicalConfig) { typedAlias() }

      assert(read(java.nio.file.Paths.get(ordinary.generatedSourcesPaths.head)) ==
        read(java.nio.file.Paths.get(canonical.generation.generatedSourcesPaths.head)))
    }
  }

  test("capture retains an eligible unnamed alias without changing emitted Verilog") {
    def unnamedAlias(): Component = {
      val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
      new Component {
        setDefinitionName("UnnamedTypedAlias")
        val input = in(Bits(width bits)).setName("payload_in")
        @dontName val aliasValue = Bits(width bits).unsetName()
        val output = out(Bits(width bits)).setName("payload_out")
        aliasValue := input
        output := aliasValue
      }
    }

    withTemporaryDirectory { directory =>
      val ordinaryDirectory = directory.resolve("ordinary")
      val canonicalDirectory = directory.resolve("canonical")
      Files.createDirectories(ordinaryDirectory)
      Files.createDirectories(canonicalDirectory)

      val ordinary = MorphVerilog(
        SpinalConfig(targetDirectory = ordinaryDirectory.toString)
      ) { unnamedAlias() }
      val canonical = MorphVerilog.generateWithCanonicalIr(
        SpinalConfig(targetDirectory = canonicalDirectory.toString)
      ) { unnamedAlias() }

      assert(read(java.nio.file.Paths.get(ordinary.generatedSourcesPaths.head)) ==
        read(java.nio.file.Paths.get(canonical.generation.generatedSourcesPaths.head)))
      val aliases = canonical.handoff.design.modules.head.declarations.filter(
        _.kind == DeclarationKind.InternalCombinational
      )
      assert(aliases.size == 1)
      assert(aliases.head.nameOrigin == NameOrigin.Unnamed)
      assert(!aliases.head.observability.preventsElimination)
      assert(aliases.head.attributes.isEmpty)
      assert(aliases.head.comments.isEmpty)
    }
  }

  test("capture rejects a reordered or ambiguous inherited phase boundary") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.phasesInserters += { phases =>
        val propagation = phases.indexWhere(_.isInstanceOf[PhasePropagateNames])
        assert(propagation >= 0)
        val moved = phases.remove(propagation)
        val crossClock = phases.indexWhere(_.getClass == classOf[PhaseCheckCrossClock])
        assert(crossClock >= 0)
        phases.insert(crossClock, moved)
      }

      val error = intercept[MorphVerilogException] {
        MorphVerilog.generateWithCanonicalIr(config) { typedAlias() }
      }
      val messages = Iterator
        .iterate(Option(error: Throwable))(_.flatMap(value => Option(value.getCause)))
        .takeWhile(_.nonEmpty)
        .flatten
        .flatMap(value => Option(value.getMessage))
        .mkString("\n")
      assert(messages.contains("MORPH-IR-PRODUCER-PHASE-PLAN-INVALID"))
    }

    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.phasesInserters += { phases => phases += null }

      val error = intercept[MorphVerilogException] {
        MorphVerilog.generateWithCanonicalIr(config) { typedAlias() }
      }
      val messages = Iterator
        .iterate(Option(error: Throwable))(_.flatMap(value => Option(value.getCause)))
        .takeWhile(_.nonEmpty)
        .flatten
        .flatMap(value => Option(value.getMessage))
        .mkString("\n")
      assert(messages.contains("MORPH-IR-PRODUCER-PHASE-PLAN-INVALID"))
    }

    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.phasesInserters += { phases =>
        val reflection = phases.indexWhere(
          _.getClass == classOf[PhaseNameNodesByReflection]
        )
        assert(reflection >= 0)
        val moved = phases.remove(reflection)
        val normalization = phases.indexWhere(
          _.getClass == classOf[PhaseNormalizeNodeInputs]
        )
        assert(normalization >= 0)
        phases.insert(normalization + 1, moved)
      }

      val error = intercept[MorphVerilogException] {
        MorphVerilog.generateWithCanonicalIr(config) { typedAlias() }
      }
      val messages = Iterator
        .iterate(Option(error: Throwable))(_.flatMap(value => Option(value.getCause)))
        .takeWhile(_.nonEmpty)
        .flatten
        .flatMap(value => Option(value.getMessage))
        .mkString("\n")
      assert(messages.contains("MORPH-IR-PRODUCER-PHASE-PLAN-INVALID"))
    }
  }

  test("capture separates configured name retention and explicit preservation from liveness") {
    withTemporaryDirectory { directory =>
      val width = HdlInt.param("WIDTH", default = 4, min = 1, max = 8)
      val report = MorphVerilog.generateWithCanonicalIr(
        SpinalConfig(targetDirectory = directory.toString)
      ) {
        new Component {
          setDefinitionName("PreservationIntent")
          val input = in(Bits(width bits)).setName("payload_in")
          val ordinary = Bits(width bits).setName("ordinary_alias")
          val explicit = Bits(width bits).setName("explicit_vital").setAsVital()
          val output = out(Bits(width bits)).setName("payload_out")
          ordinary := input
          explicit := ordinary
          output := explicit
        }
      }

      val declarations = report.handoff.design.modules.head.declarations
      val ordinary = declarations.find(
        _.nameOrigin == NameOrigin.Explicit("ordinary_alias")
      ).get
      val explicit = declarations.find(
        _.nameOrigin == NameOrigin.Explicit("explicit_vital")
      ).get
      assert(ordinary.observability.keep)
      assert(!ordinary.observability.preserve)
      assert(ordinary.observability.preventsElimination)
      assert(explicit.observability.keep)
      assert(explicit.observability.preserve)
    }
  }

  test("publisher runs once after successful publication with the exact handoff") {
    withTemporaryDirectory { directory =>
      val target = directory.resolve("published.v")
      val received = new AtomicReference[CanonicalIrHandoff]()
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = target.getFileName.toString

      val report = MorphVerilog.publishCanonicalIr(
        config,
        new morphhdl.ir.v1.CanonicalIrPublisher {
          override def publish(handoff: CanonicalIrHandoff): Unit = {
            assert(Files.isRegularFile(target))
            assert(received.compareAndSet(null, handoff))
          }
        }
      ) { typedAlias() }

      assert(received.get() eq report.handoff)
    }
  }

  test("bounded capture retains native declaration attributes, comments and literals exactly") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "decorated_literal.v"
      val width = HdlInt.param("WIDTH", default = 4, min = 4, max = 4)
      val report = MorphVerilog.generateWithCanonicalIr(config) {
        new Component {
          setDefinitionName("DecoratedLiteral")
          val input = in(Bits(width bits)).setName("payload_in")
          val anchored = Bits(width bits).setName("anchored_value").dontSimplifyIt()
          anchored.addAttribute("keep_hierarchy", "yes")
          anchored.addTag(new CommentTag("canonical anchor"))
          val output = out(Bits(width bits)).setName("payload_out")
          anchored := B(10, 4 bits)
          output := anchored
        }
      }

      val module = report.handoff.design.modules.head
      val anchored = module.declarations.find {
        _.nameOrigin == NameOrigin.Explicit("anchored_value")
      }.get
      assert(anchored.attributes.size == 1)
      assert(anchored.attributes.head.name == "keep_hierarchy")
      assert(anchored.attributes.head.value.contains("yes"))
      assert(anchored.attributes.head.kind == AttributeKind.Backend)
      assert(anchored.comments.map(_.text) == Vector("canonical anchor"))
      assert(anchored.observability.complete)
      assert(anchored.observability.preserve)
      assert(anchored.sourceLocation.isEmpty)
      assert(anchored.attributes.forall(_.sourceLocation.isEmpty))
      assert(anchored.comments.forall(_.sourceLocation.isEmpty))

      val literal = module.drivers.map(_.value).collectFirst {
        case value: RtlExpr.Literal => value
      }.get
      assert(literal.value == 10)
      assert(literal.width == 4)
      assert(!literal.signed)
    }
  }

  test("bounded capture preserves reflected unnamed and generated name provenance") {
    withTemporaryDirectory { directory =>
      val width = HdlInt.param("WIDTH", default = 4, min = 1, max = 8)
      val report = MorphVerilog.generateWithCanonicalIr(
        SpinalConfig(targetDirectory = directory.toString)
      ) {
        new Component {
          setDefinitionName("NameOriginProvenance")
          val input = in(Bits(width bits)).setName("payload_in")
          val reflectedStrong = Bits(width bits)
            .setName("reflected_strong", Nameable.DATAMODEL_STRONG)
            .dontSimplifyIt()
          val reflectedWeak = Bits(width bits)
            .setName("reflected_weak", Nameable.DATAMODEL_WEAK)
            .dontSimplifyIt()
          @dontName val generated = Bits(width bits)
            .setName("generated_temporary", Nameable.REMOVABLE)
            .dontSimplifyIt()
          @dontName val unnamed = Bits(width bits).unsetName().dontSimplifyIt()
          val output = out(Bits(width bits)).setName("payload_out")
          reflectedStrong := input
          reflectedWeak := reflectedStrong
          generated := reflectedWeak
          unnamed := generated
          output := unnamed
        }
      }

      val declarations = report.handoff.design.modules.head.declarations
      val origins = declarations.map(_.nameOrigin)
      assert(origins.contains(NameOrigin.Reflected("reflected_strong")))
      assert(origins.contains(NameOrigin.Reflected("reflected_weak")))
      assert(origins.contains(NameOrigin.Unnamed))
      assert(origins.contains(NameOrigin.Generated))
      assert(!origins.contains(NameOrigin.Explicit("reflected_strong")))
      assert(
        declarations
          .find(_.nameOrigin == NameOrigin.Reflected("reflected_strong"))
          .get
          .observability
          .keep
      )
      assert(
        declarations
          .find(_.nameOrigin == NameOrigin.Reflected("reflected_weak"))
          .get
          .observability
          .keep
      )
      assert(
        !declarations
          .find(_.nameOrigin == NameOrigin.Generated)
          .get
          .observability
          .keep
      )
      assert(
        !declarations
          .find(_.nameOrigin == NameOrigin.Unnamed)
          .get
          .observability
          .keep
      )
    }
  }

  test("equal concrete witnesses from distinct typed roots remain distinct parameters") {
    withTemporaryDirectory { directory =>
      val leftWidth = HdlInt.param("LEFT_WIDTH", default = 8, min = 1, max = 16)
      val rightWidth = HdlInt.param("RIGHT_WIDTH", default = 8, min = 1, max = 16)
      val report = MorphVerilog.generateWithCanonicalIr(
        SpinalConfig(targetDirectory = directory.toString)
      ) {
        new Component {
          setDefinitionName("DistinctWidthRoots")
          val leftInput = in(Bits(leftWidth bits)).setName("left_payload")
          val rightInput = in(Bits(rightWidth bits)).setName("right_payload")
          val leftOutput = out(Bits(leftWidth bits)).setName("left_result")
          leftOutput := leftInput
        }
      }

      val module = report.handoff.design.modules.head
      assert(module.parameters.map(_.name).toSet == Set("LEFT_WIDTH", "RIGHT_WIDTH"))
      assert(module.parameters.map(_.id).distinct.size == 2)
      val widths = module.declarations.flatMap(_.packedType.map(_.width)).collect {
        case IntExpr.ParameterRef(parameter) => parameter
      }
      assert(widths.distinct.size == 2)
    }
  }

  test("producer cardinality preflight rejects oversized domains before enumeration") {
    val preflight = MorphHdlCanonicalIrProducer.getClass.getDeclaredMethods
      .find(method =>
        method.getName.endsWith("parameterDomainCardinality") &&
          method.getParameterCount == 2
      )
      .get
    preflight.setAccessible(true)
    val invocation = intercept[InvocationTargetException] {
      preflight.invoke(
        MorphHdlCanonicalIrProducer,
        BigInt(1),
        BigInt(65537)
      )
    }
    val failure = invocation.getCause.asInstanceOf[MorphHdlCanonicalIrProducerException]
    assert(failure.code == "MORPH-IR-PRODUCER-PARAMETER-DOMAIN-TOO-LARGE")
  }

  test("semantic identities and edges are independent of component and signal spellings") {
    withTemporaryDirectory { directory =>
      def generate(
          child: String,
          definition: String,
          input: String,
          alias: String,
          output: String
      ): MorphCanonicalIrReport = {
        val target = directory.resolve(child)
        Files.createDirectories(target)
        val config = SpinalConfig(targetDirectory = target.toString)
        config.netlistFileName = "design.v"
        MorphVerilog.generateWithCanonicalIr(config) {
          typedAlias(definition, input, alias, output)
        }
      }

      val first = generate("first", "FirstName", "first_in", "first_alias", "first_out")
      val second = generate("second", "OtherName", "renamed_in", "renamed_alias", "renamed_out")
      val repeated = generate("repeated", "FirstName", "first_in", "first_alias", "first_out")

      def semanticProjection(report: MorphCanonicalIrReport) = {
        val module = report.handoff.design.modules.head
        val parameters = module.parameters.map {
          case parameter: IntegerParameter =>
            (parameter.id, "integer", parameter.default.toString)
          case parameter: BooleanParameter =>
            (parameter.id, "boolean", parameter.default.toString)
        }
        (
          parameters,
          module.scopes.map(scope => (scope.id, scope.parent, scope.kind)),
          module.declarations.map(declaration =>
            (declaration.id, declaration.owner, declaration.kind, declaration.packedType)
          ),
          module.drivers.map(driver =>
            (driver.id, driver.owner, driver.target, driver.kind, driver.coverage,
              driver.value.referencedSymbols)
          )
        )
      }

      assert(semanticProjection(first) == semanticProjection(second))
      assert(first.handoff.design == repeated.handoff.design)
    }
  }

  test("unsupported native constructs fail closed with stable producer diagnostics") {
    withTemporaryDirectory { directory =>
      def rejected(name: String)(component: => Component): String = {
        val target = directory.resolve(name)
        Files.createDirectories(target)
        val error = intercept[MorphVerilogException] {
          MorphVerilog.generateWithCanonicalIr(
            SpinalConfig(targetDirectory = target.toString)
          )(component)
        }
        Iterator
          .iterate(Option(error: Throwable))(_.flatMap(value => Option(value.getCause)))
          .takeWhile(_.nonEmpty)
          .flatten
          .map(value => Option(value.getMessage).getOrElse(""))
          .mkString("\n")
      }

      val register = rejected("register") {
        new Component {
          val input = in(Bool()).setName("input")
          val output = out(Bool()).setName("output")
          val state = Reg(Bool()).setName("state")
          state := input
          output := state
        }
      }
      assert(register.contains("MORPH-IR-PRODUCER-REGISTER-UNSUPPORTED"))

      val hierarchy = rejected("hierarchy") {
        new Component {
          val input = in(Bool()).setName("input")
          val output = out(Bool()).setName("output")
          val child = new Component {
            val input = in(Bool()).setName("input")
            val output = out(Bool()).setName("output")
            output := input
          }
          child.input := input
          output := child.output
        }
      }
      assert(hierarchy.contains("MORPH-IR-PRODUCER-HIERARCHY-UNSUPPORTED"))

      val memory = rejected("memory") {
        new Component {
          val address = in(UInt(2 bits)).setName("read_address")
          val output = out(Bits(8 bits)).setName("read_payload")
          val storage = Mem(Bits(8 bits), wordCount = 4)
          output := storage.readAsync(address)
        }
      }
      assert(memory.contains("MORPH-IR-PRODUCER-MEMORY-UNSUPPORTED"))

      val compoundWidth = rejected("compound-width") {
        val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 16)
        new Component {
          val input = in(Bits((width + 1) bits)).setName("input")
          val output = out(Bits((width + 1) bits)).setName("output")
          output := input
        }
      }
      assert(compoundWidth.contains("MORPH-IR-PRODUCER-WIDTH-EXPRESSION-UNSUPPORTED"))

      val expression = rejected("expression") {
        new Component {
          val input = in(Bits(8 bits)).setName("input")
          val output = out(Bits(8 bits)).setName("output")
          output := ~input
        }
      }
      assert(expression.contains("MORPH-IR-PRODUCER-EXPRESSION-UNSUPPORTED"))

      val assignmentOverride = rejected("assignment-override") {
        new Component {
          val first = in(Bool()).setName("first_payload")
          val second = in(Bool()).setName("second_payload")
          val result = out(Bool()).setName("selected_payload")
          result.addTag(allowAssignmentOverride)
          result := first
          result := second
        }
      }
      assert(
        assignmentOverride.contains(
          "MORPH-IR-PRODUCER-ASSIGNMENT-OVERRIDE-UNSUPPORTED"
        )
      )
    }
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-canonical-ir-")
    try body(directory)
    finally {
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }
}

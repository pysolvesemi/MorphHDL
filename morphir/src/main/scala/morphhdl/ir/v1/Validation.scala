package morphhdl.ir.v1

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/** Stable diagnostic identifiers emitted by the v1 validator. */
object IrDiagnosticCode {
  val DiagnosticLimitInvalid = "MORPH-IR-V1-DIAGNOSTIC-LIMIT-INVALID"
  val DiagnosticLimitReached = "MORPH-IR-V1-DIAGNOSTIC-LIMIT-REACHED"
  val DesignMissing = "MORPH-IR-V1-DESIGN-MISSING"
  val UnsupportedVersion = "MORPH-IR-V1-UNSUPPORTED-VERSION"
  val StageMismatch = "MORPH-IR-V1-STAGE-MISMATCH"
  val TopModuleUnresolved = "MORPH-IR-V1-TOP-MODULE-UNRESOLVED"
  val ModuleMissing = "MORPH-IR-V1-MODULE-MISSING"
  val ModuleIdMissing = "MORPH-IR-V1-MODULE-ID-MISSING"
  val ModuleIdInvalid = "MORPH-IR-V1-MODULE-ID-INVALID"
  val ModuleIdDuplicate = "MORPH-IR-V1-MODULE-ID-DUPLICATE"
  val ModuleNameMissing = "MORPH-IR-V1-MODULE-NAME-MISSING"
  val CollectionMissing = "MORPH-IR-V1-COLLECTION-MISSING"
  val LocationContainerMissing = "MORPH-IR-V1-LOCATION-CONTAINER-MISSING"
  val LocationInvalid = "MORPH-IR-V1-LOCATION-INVALID"
  val ParameterMissing = "MORPH-IR-V1-PARAMETER-MISSING"
  val ParameterIdMissing = "MORPH-IR-V1-PARAMETER-ID-MISSING"
  val ParameterIdInvalid = "MORPH-IR-V1-PARAMETER-ID-INVALID"
  val ParameterIdDuplicate = "MORPH-IR-V1-PARAMETER-ID-DUPLICATE"
  val ParameterNameDuplicate = "MORPH-IR-V1-PARAMETER-NAME-DUPLICATE"
  val ParameterNameMissing = "MORPH-IR-V1-PARAMETER-NAME-MISSING"
  val ParameterDomainMissing = "MORPH-IR-V1-PARAMETER-DOMAIN-MISSING"
  val ParameterDomainEmpty = "MORPH-IR-V1-PARAMETER-DOMAIN-EMPTY"
  val ParameterDomainTooLarge = "MORPH-IR-V1-PARAMETER-DOMAIN-TOO-LARGE"
  val ParameterDomainDuplicateValue = "MORPH-IR-V1-PARAMETER-DOMAIN-DUPLICATE-VALUE"
  val ParameterDomainBoundsInvalid = "MORPH-IR-V1-PARAMETER-DOMAIN-BOUNDS-INVALID"
  val ParameterDomainBoundsMismatch = "MORPH-IR-V1-PARAMETER-DOMAIN-BOUNDS-MISMATCH"
  val ParameterDefaultOutsideDomain = "MORPH-IR-V1-PARAMETER-DEFAULT-OUTSIDE-DOMAIN"
  val ParameterUnresolved = "MORPH-IR-V1-PARAMETER-UNRESOLVED"
  val ParameterKindMismatch = "MORPH-IR-V1-PARAMETER-KIND-MISMATCH"
  val ScopeMissing = "MORPH-IR-V1-SCOPE-MISSING"
  val ScopeIdMissing = "MORPH-IR-V1-SCOPE-ID-MISSING"
  val ScopeIdInvalid = "MORPH-IR-V1-SCOPE-ID-INVALID"
  val ScopeIdDuplicate = "MORPH-IR-V1-SCOPE-ID-DUPLICATE"
  val ScopeRootInvalid = "MORPH-IR-V1-SCOPE-ROOT-INVALID"
  val ScopeParentRequired = "MORPH-IR-V1-SCOPE-PARENT-REQUIRED"
  val ScopeKindMissing = "MORPH-IR-V1-SCOPE-KIND-MISSING"
  val ScopeParentUnresolved = "MORPH-IR-V1-SCOPE-PARENT-UNRESOLVED"
  val ScopeCycle = "MORPH-IR-V1-SCOPE-CYCLE"
  val GenerateIndexMissing = "MORPH-IR-V1-GENERATE-INDEX-MISSING"
  val GenerateIndexIdMissing = "MORPH-IR-V1-GENERATE-INDEX-ID-MISSING"
  val GenerateIndexIdInvalid = "MORPH-IR-V1-GENERATE-INDEX-ID-INVALID"
  val GenerateIndexIdDuplicate = "MORPH-IR-V1-GENERATE-INDEX-ID-DUPLICATE"
  val GenerateIndexOwnerUnresolved = "MORPH-IR-V1-GENERATE-INDEX-OWNER-UNRESOLVED"
  val GenerateIndexOwnerKindInvalid = "MORPH-IR-V1-GENERATE-INDEX-OWNER-KIND-INVALID"
  val GenerateIndexNameMissing = "MORPH-IR-V1-GENERATE-INDEX-NAME-MISSING"
  val GenerateIndexDomainInvalid = "MORPH-IR-V1-GENERATE-INDEX-DOMAIN-INVALID"
  val GenerateIndexUnresolved = "MORPH-IR-V1-GENERATE-INDEX-UNRESOLVED"
  val GenerateIndexNotVisible = "MORPH-IR-V1-GENERATE-INDEX-NOT-VISIBLE"
  val DeclarationMissing = "MORPH-IR-V1-DECLARATION-MISSING"
  val SymbolIdMissing = "MORPH-IR-V1-SYMBOL-ID-MISSING"
  val SymbolIdInvalid = "MORPH-IR-V1-SYMBOL-ID-INVALID"
  val SymbolIdDuplicate = "MORPH-IR-V1-SYMBOL-ID-DUPLICATE"
  val DeclarationScopeUnresolved = "MORPH-IR-V1-DECLARATION-SCOPE-UNRESOLVED"
  val DeclarationKindMissing = "MORPH-IR-V1-DECLARATION-KIND-MISSING"
  val PortDirectionMissing = "MORPH-IR-V1-PORT-DIRECTION-MISSING"
  val PackedTypeMissing = "MORPH-IR-V1-PACKED-TYPE-MISSING"
  val PackedSignednessMissing = "MORPH-IR-V1-PACKED-SIGNEDNESS-MISSING"
  val PackedValueSemanticsMissing = "MORPH-IR-V1-PACKED-VALUE-SEMANTICS-MISSING"
  val PackedValueSemanticsMismatch = "MORPH-IR-V1-PACKED-VALUE-SEMANTICS-MISMATCH"
  val PackedWidthNotPositive = "MORPH-IR-V1-PACKED-WIDTH-NOT-POSITIVE"
  val NameOriginMissing = "MORPH-IR-V1-NAME-ORIGIN-MISSING"
  val NameOriginUnknown = "MORPH-IR-V1-NAME-ORIGIN-UNKNOWN"
  val NameOriginValueInvalid = "MORPH-IR-V1-NAME-ORIGIN-VALUE-INVALID"
  val ObservabilityMissing = "MORPH-IR-V1-OBSERVABILITY-MISSING"
  val ObservabilityIncomplete = "MORPH-IR-V1-OBSERVABILITY-INCOMPLETE"
  val AttributeMissing = "MORPH-IR-V1-ATTRIBUTE-MISSING"
  val AttributeNameMissing = "MORPH-IR-V1-ATTRIBUTE-NAME-MISSING"
  val AttributeValueMissing = "MORPH-IR-V1-ATTRIBUTE-VALUE-MISSING"
  val AttributeKindMissing = "MORPH-IR-V1-ATTRIBUTE-KIND-MISSING"
  val CommentMissing = "MORPH-IR-V1-COMMENT-MISSING"
  val CommentTextMissing = "MORPH-IR-V1-COMMENT-TEXT-MISSING"
  val DriverMissing = "MORPH-IR-V1-DRIVER-MISSING"
  val DriverIdMissing = "MORPH-IR-V1-DRIVER-ID-MISSING"
  val DriverIdInvalid = "MORPH-IR-V1-DRIVER-ID-INVALID"
  val DriverIdDuplicate = "MORPH-IR-V1-DRIVER-ID-DUPLICATE"
  val DriverScopeUnresolved = "MORPH-IR-V1-DRIVER-SCOPE-UNRESOLVED"
  val DriverTargetUnresolved = "MORPH-IR-V1-DRIVER-TARGET-UNRESOLVED"
  val DriverTargetNotVisible = "MORPH-IR-V1-DRIVER-TARGET-NOT-VISIBLE"
  val DriverKindMissing = "MORPH-IR-V1-DRIVER-KIND-MISSING"
  val DriverCoverageMissing = "MORPH-IR-V1-DRIVER-COVERAGE-MISSING"
  val DriverCoverageUnknown = "MORPH-IR-V1-DRIVER-COVERAGE-UNKNOWN"
  val RtlExpressionMissing = "MORPH-IR-V1-RTL-EXPRESSION-MISSING"
  val RtlOperatorMissing = "MORPH-IR-V1-RTL-OPERATOR-MISSING"
  val RtlLiteralInvalid = "MORPH-IR-V1-RTL-LITERAL-INVALID"
  val RtlConcatEmpty = "MORPH-IR-V1-RTL-CONCAT-EMPTY"
  val ReferenceIdDuplicate = "MORPH-IR-V1-REFERENCE-ID-DUPLICATE"
  val ReferenceIdMissing = "MORPH-IR-V1-REFERENCE-ID-MISSING"
  val ReferenceIdInvalid = "MORPH-IR-V1-REFERENCE-ID-INVALID"
  val ReferenceOwnerUnresolved = "MORPH-IR-V1-REFERENCE-OWNER-UNRESOLVED"
  val ReferenceOwnerMismatch = "MORPH-IR-V1-REFERENCE-OWNER-MISMATCH"
  val RtlReferenceUnresolved = "MORPH-IR-V1-RTL-REFERENCE-UNRESOLVED"
  val RtlReferenceNotVisible = "MORPH-IR-V1-RTL-REFERENCE-NOT-VISIBLE"
  val IntExpressionMissing = "MORPH-IR-V1-INT-EXPRESSION-MISSING"
  val BoolExpressionMissing = "MORPH-IR-V1-BOOL-EXPRESSION-MISSING"
  val IntegerLiteralMissing = "MORPH-IR-V1-INTEGER-LITERAL-MISSING"
  val IntegerDivisorMayBeZero = "MORPH-IR-V1-INTEGER-DIVISOR-MAY-BE-ZERO"
  val CeilLog2OperandInvalid = "MORPH-IR-V1-CEIL-LOG2-OPERAND-INVALID"
  val AddressWidthOperandInvalid = "MORPH-IR-V1-ADDRESS-WIDTH-OPERAND-INVALID"
  val Pow2ExponentInvalid = "MORPH-IR-V1-POW2-EXPONENT-INVALID"
  val PartSelectOffsetInvalid = "MORPH-IR-V1-PART-SELECT-OFFSET-INVALID"
  val PartSelectWidthInvalid = "MORPH-IR-V1-PART-SELECT-WIDTH-INVALID"
  val ResizeWidthInvalid = "MORPH-IR-V1-RESIZE-WIDTH-INVALID"
  val ExactEvaluationLimitReached = "MORPH-IR-V1-EXACT-EVALUATION-LIMIT-REACHED"
}

final case class IrDiagnostic(
    code: String,
    message: String,
    path: Vector[String],
    location: Option[SourceLocation]
) {
  def pathString: String = path.mkString("/")
}

final case class IrDiagnosticSet private (values: Vector[IrDiagnostic]) {
  def codes: Vector[String] = values.map(_.code)
  def isEmpty: Boolean = values.isEmpty
  def size: Int = values.size
}

object IrDiagnosticSet {
  private[v1] def from(values: Vector[IrDiagnostic]): IrDiagnosticSet = {
    val ordered = values.distinct.sortBy { diagnostic =>
      val location = diagnostic.location match {
        case Some(value) if value != null =>
          (Option(value.path).getOrElse(""), value.line, value.column)
        case _ => ("", 0, 0)
      }
      (
        location._1,
        location._2,
        location._3,
        diagnostic.pathString,
        diagnostic.code,
        diagnostic.message
      )
    }
    new IrDiagnosticSet(ordered)
  }
}

/** Validated, deterministically normalized canonical IR. */
final class ValidatedDesign private[v1] (val value: Design)

object CanonicalIrValidator {
  val DefaultMaximumDiagnostics: Int = 256
  val MaximumParameterDomainSize: Int = 65536
  val MaximumExactEvaluationCases: Int = 65536

  def validate(
      design: Design,
      maxErrors: Int = DefaultMaximumDiagnostics
  ): Either[IrDiagnosticSet, ValidatedDesign] = {
    val diagnostics = new DiagnosticCollector(maxErrors)
    if (maxErrors < 1) {
      diagnostics.add(
        IrDiagnosticCode.DiagnosticLimitInvalid,
        s"diagnostic limit must be positive, observed $maxErrors",
        Vector("design", "diagnostics"),
        None
      )
      return Left(diagnostics.result())
    }

    if (design == null) {
      diagnostics.add(
        IrDiagnosticCode.DesignMissing,
        "canonical design must not be null",
        Vector("design"),
        None
      )
      return Left(diagnostics.result())
    }

    if (design.version != CanonicalIrSchema.schemaVersion) {
      diagnostics.add(
        IrDiagnosticCode.UnsupportedVersion,
        s"canonical design requires schema ${CanonicalIrSchema.schemaVersion}, observed ${Option(design.version).map(_.toString).getOrElse("<null>")}",
        Vector("design", "version"),
        None
      )
    }
    if (design.stage != CanonicalIrSchema.stage) {
      diagnostics.add(
        IrDiagnosticCode.StageMismatch,
        s"canonical design requires stage '${CanonicalIrSchema.stage.label}'",
        Vector("design", "stage"),
        None
      )
    }

    val modules = requiredVector(
      design.modules,
      "design modules",
      Vector("design", "modules"),
      diagnostics,
      None
    )
    modules.zipWithIndex.foreach { case (module, index) =>
      if (module == null) {
        diagnostics.add(
          IrDiagnosticCode.ModuleMissing,
          "canonical module entry must not be null",
          Vector("design", "modules", index.toString),
          None
        )
      } else {
        validateIdentifier(
        moduleIdValue(module.id),
          IrDiagnosticCode.ModuleIdMissing,
          IrDiagnosticCode.ModuleIdInvalid,
          "module id",
          Vector("design", "modules", index.toString, "id"),
          safeLocation(module.sourceLocation),
          diagnostics
        )
      }
    }
    val nonNullModules = modules.filter(_ != null)
    addDuplicates(
      nonNullModules.map(module => moduleIdValue(module.id) -> safeLocation(module.sourceLocation)),
      IrDiagnosticCode.ModuleIdDuplicate,
      "module id",
      Vector("design", "modules"),
      diagnostics
    )

    val moduleById = nonNullModules
      .flatMap(module => Option(module.id).filter(validModuleId).map(_ -> module))
      .groupBy(_._1)
      .map { case (id, values) => id -> values.head._2 }
    val topIdValid = validateIdentifier(
      moduleIdValue(design.top),
      IrDiagnosticCode.ModuleIdMissing,
      IrDiagnosticCode.ModuleIdInvalid,
      "top module id",
      Vector("design", "top"),
      None,
      diagnostics
    )
    if (!topIdValid || !moduleById.contains(design.top)) {
      diagnostics.add(
        IrDiagnosticCode.TopModuleUnresolved,
        s"top module '${idText(design.top)}' is not declared exactly once",
        Vector("design", "top"),
        None
      )
    }

    nonNullModules.foreach(module => validateModule(module, diagnostics))
    validateGlobalIdentities(nonNullModules, diagnostics)

    val result = diagnostics.result()
    if (result.isEmpty)
      Right(new ValidatedDesign(CanonicalIrNormalizer.normalize(design)))
    else Left(result)
  }

  private final case class IntInterval(minimum: BigInt, maximum: BigInt) {
    def includes(value: BigInt): Boolean = minimum <= value && value <= maximum
    def union(that: IntInterval): IntInterval =
      IntInterval(minimum.min(that.minimum), maximum.max(that.maximum))
  }

  private final class DiagnosticCollector(requestedLimit: Int) {
    private val limit = math.max(1, requestedLimit)
    private val values = ArrayBuffer.empty[IrDiagnostic]
    private var truncated = false

    def add(
        code: String,
        message: String,
        path: Vector[String],
        location: Option[SourceLocation]
    ): Unit = {
      if (!truncated) {
        val diagnostic = IrDiagnostic(code, message, path, safeLocation(location))
        if (values.size < limit) values += diagnostic
        else {
          truncated = true
          val marker = IrDiagnostic(
            IrDiagnosticCode.DiagnosticLimitReached,
            s"canonical IR validation stopped after reaching the diagnostic limit $limit",
            Vector("design", "diagnostics"),
            None
          )
          values(values.size - 1) = marker
        }
      }
    }

    def result(): IrDiagnosticSet = IrDiagnosticSet.from(values.toVector)
  }

  private def validateModule(
      module: Module,
      diagnostics: DiagnosticCollector
  ): Unit = {
    val modulePath = Vector("design", "modules", idText(module.id))
    val moduleLocation = safeLocation(module.sourceLocation)
    validateLocationContainer(module.sourceLocation, modulePath :+ "source", diagnostics)
    if (!nonEmpty(module.logicalName)) {
      diagnostics.add(
        IrDiagnosticCode.ModuleNameMissing,
        "module logical name must be non-empty",
        modulePath :+ "logical-name",
        moduleLocation
      )
    }

    val parameters = requiredVector(
      module.parameters,
      "module parameters",
      modulePath :+ "parameters",
      diagnostics,
      moduleLocation
    )
    val scopes = requiredVector(
      module.scopes,
      "module scopes",
      modulePath :+ "scopes",
      diagnostics,
      moduleLocation
    )
    val generateIndices = requiredVector(
      module.generateIndices,
      "module generate indices",
      modulePath :+ "generate-indices",
      diagnostics,
      moduleLocation
    )
    val declarations = requiredVector(
      module.declarations,
      "module declarations",
      modulePath :+ "declarations",
      diagnostics,
      moduleLocation
    )
    val drivers = requiredVector(
      module.drivers,
      "module drivers",
      modulePath :+ "drivers",
      diagnostics,
      moduleLocation
    )

    validateNullEntries(
      parameters,
      IrDiagnosticCode.ParameterMissing,
      "parameter",
      modulePath :+ "parameters",
      moduleLocation,
      diagnostics
    )
    validateNullEntries(
      scopes,
      IrDiagnosticCode.ScopeMissing,
      "scope",
      modulePath :+ "scopes",
      moduleLocation,
      diagnostics
    )
    validateNullEntries(
      generateIndices,
      IrDiagnosticCode.GenerateIndexMissing,
      "generate index",
      modulePath :+ "generate-indices",
      moduleLocation,
      diagnostics
    )
    validateNullEntries(
      declarations,
      IrDiagnosticCode.DeclarationMissing,
      "declaration",
      modulePath :+ "declarations",
      moduleLocation,
      diagnostics
    )
    validateNullEntries(
      drivers,
      IrDiagnosticCode.DriverMissing,
      "driver",
      modulePath :+ "drivers",
      moduleLocation,
      diagnostics
    )

    val validParameters = parameters.filter(_ != null)
    val validScopes = scopes.filter(_ != null)
    val validIndices = generateIndices.filter(_ != null)
    val validDeclarations = declarations.filter(_ != null)
    val validDrivers = drivers.filter(_ != null)

    parameters.zipWithIndex.foreach { case (value, index) =>
      if (value != null) {
        validateIdentifier(
          parameterIdValue(value.id),
          IrDiagnosticCode.ParameterIdMissing,
          IrDiagnosticCode.ParameterIdInvalid,
          "parameter id",
          modulePath :+ "parameters" :+ index.toString :+ "id",
          safeLocation(value.sourceLocation),
          diagnostics
        )
      }
    }
    scopes.zipWithIndex.foreach { case (value, index) =>
      if (value != null) {
        validateIdentifier(
          scopeIdValue(value.id),
          IrDiagnosticCode.ScopeIdMissing,
          IrDiagnosticCode.ScopeIdInvalid,
          "scope id",
          modulePath :+ "scopes" :+ index.toString :+ "id",
          safeLocation(value.sourceLocation),
          diagnostics
        )
      }
    }
    generateIndices.zipWithIndex.foreach { case (value, index) =>
      if (value != null) {
        validateIdentifier(
          generateIndexIdValue(value.id),
          IrDiagnosticCode.GenerateIndexIdMissing,
          IrDiagnosticCode.GenerateIndexIdInvalid,
          "generate-index id",
          modulePath :+ "generate-indices" :+ index.toString :+ "id",
          safeLocation(value.sourceLocation),
          diagnostics
        )
      }
    }
    declarations.zipWithIndex.foreach { case (value, index) =>
      if (value != null) {
        validateIdentifier(
          symbolIdValue(value.id),
          IrDiagnosticCode.SymbolIdMissing,
          IrDiagnosticCode.SymbolIdInvalid,
          "symbol id",
          modulePath :+ "declarations" :+ index.toString :+ "id",
          safeLocation(value.sourceLocation),
          diagnostics
        )
      }
    }
    drivers.zipWithIndex.foreach { case (value, index) =>
      if (value != null) {
        validateIdentifier(
          driverIdValue(value.id),
          IrDiagnosticCode.DriverIdMissing,
          IrDiagnosticCode.DriverIdInvalid,
          "driver id",
          modulePath :+ "drivers" :+ index.toString :+ "id",
          safeLocation(value.sourceLocation),
          diagnostics
        )
      }
    }

    addDuplicates(
      validParameters.map(value => parameterIdValue(value.id) -> safeLocation(value.sourceLocation)),
      IrDiagnosticCode.ParameterIdDuplicate,
      "parameter id",
      modulePath :+ "parameters",
      diagnostics
    )
    addDuplicates(
      validParameters.map(value => Option(value.name).getOrElse("") -> safeLocation(value.sourceLocation)),
      IrDiagnosticCode.ParameterNameDuplicate,
      "parameter name",
      modulePath :+ "parameters",
      diagnostics
    )
    addDuplicates(
      validScopes.map(value => scopeIdValue(value.id) -> safeLocation(value.sourceLocation)),
      IrDiagnosticCode.ScopeIdDuplicate,
      "scope id",
      modulePath :+ "scopes",
      diagnostics
    )
    addDuplicates(
      validIndices.map(value => generateIndexIdValue(value.id) -> safeLocation(value.sourceLocation)),
      IrDiagnosticCode.GenerateIndexIdDuplicate,
      "generate-index id",
      modulePath :+ "generate-indices",
      diagnostics
    )
    addDuplicates(
      validDeclarations.map(value => symbolIdValue(value.id) -> safeLocation(value.sourceLocation)),
      IrDiagnosticCode.SymbolIdDuplicate,
      "symbol id",
      modulePath :+ "declarations",
      diagnostics
    )
    addDuplicates(
      validDrivers.map(value => driverIdValue(value.id) -> safeLocation(value.sourceLocation)),
      IrDiagnosticCode.DriverIdDuplicate,
      "driver id",
      modulePath :+ "drivers",
      diagnostics
    )

    validParameters.foreach(value => validateParameter(value, modulePath, diagnostics))

    val scopeById = validScopes
      .flatMap(value => Option(value.id).filter(validScopeId).map(_ -> value))
      .groupBy(_._1)
      .map { case (id, values) => id -> values.head._2 }
    validateScopes(validScopes, scopeById, modulePath, diagnostics)
    validateGenerateIndices(validIndices, scopeById, modulePath, diagnostics)

    val integerParameters = validParameters.collect {
      case value: IntegerParameter if validParameterId(value.id) => value.id -> value
    }.toMap
    val booleanParameters = validParameters.collect {
      case value: BooleanParameter if validParameterId(value.id) => value.id -> value
    }.toMap
    val indexById = validIndices
      .flatMap(value => Option(value.id).filter(validGenerateIndexId).map(_ -> value))
      .groupBy(_._1)
      .map { case (id, values) => id -> values.head._2 }
    val declarationById = validDeclarations
      .flatMap(value => Option(value.id).filter(validSymbolId).map(_ -> value))
      .groupBy(_._1)
      .map { case (id, values) => id -> values.head._2 }

    val references = validDrivers.flatMap(driver => collectReferences(driver.value))
    addDuplicates(
      references.map(value => referenceIdValue(value.id) -> safeLocation(value.sourceLocation)),
      IrDiagnosticCode.ReferenceIdDuplicate,
      "reference id",
      modulePath :+ "references",
      diagnostics
    )

    validDeclarations.foreach { declaration =>
      validateDeclaration(
        declaration,
        scopeById,
        integerParameters,
        booleanParameters,
        indexById,
        modulePath,
        diagnostics
      )
    }
    validDrivers.foreach { driver =>
      validateDriver(
        driver,
        scopeById,
        declarationById,
        integerParameters,
        booleanParameters,
        indexById,
        modulePath,
        diagnostics
      )
    }
  }

  private def validateGlobalIdentities(
      modules: Vector[Module],
      diagnostics: DiagnosticCollector
  ): Unit = {
    val parameters = modules.flatMap { module =>
      Option(module.parameters).getOrElse(Vector.empty).filter(_ != null).flatMap { parameter =>
        Some(parameterIdValue(parameter.id) -> safeLocation(parameter.sourceLocation))
      }
    }
    addDuplicates(
      parameters,
      IrDiagnosticCode.ParameterIdDuplicate,
      "design-wide parameter id",
      Vector("design", "parameters"),
      diagnostics
    )
    val scopes = modules.flatMap { module =>
      Option(module.scopes).getOrElse(Vector.empty).filter(_ != null).flatMap { scope =>
        Some(scopeIdValue(scope.id) -> safeLocation(scope.sourceLocation))
      }
    }
    addDuplicates(
      scopes,
      IrDiagnosticCode.ScopeIdDuplicate,
      "design-wide scope id",
      Vector("design", "scopes"),
      diagnostics
    )
    val generateIndices = modules.flatMap { module =>
      Option(module.generateIndices).getOrElse(Vector.empty).filter(_ != null).flatMap { index =>
        Some(generateIndexIdValue(index.id) -> safeLocation(index.sourceLocation))
      }
    }
    addDuplicates(
      generateIndices,
      IrDiagnosticCode.GenerateIndexIdDuplicate,
      "design-wide generate-index id",
      Vector("design", "generate-indices"),
      diagnostics
    )
    val symbols = modules.flatMap { module =>
      Option(module.declarations).getOrElse(Vector.empty).filter(_ != null).flatMap { declaration =>
        Some(symbolIdValue(declaration.id) -> safeLocation(declaration.sourceLocation))
      }
    }
    addDuplicates(
      symbols,
      IrDiagnosticCode.SymbolIdDuplicate,
      "design-wide symbol id",
      Vector("design", "symbols"),
      diagnostics
    )
    val drivers = modules.flatMap { module =>
      Option(module.drivers).getOrElse(Vector.empty).filter(_ != null).flatMap { driver =>
        Some(driverIdValue(driver.id) -> safeLocation(driver.sourceLocation))
      }
    }
    addDuplicates(
      drivers,
      IrDiagnosticCode.DriverIdDuplicate,
      "design-wide driver id",
      Vector("design", "drivers"),
      diagnostics
    )
    val references = modules.flatMap { module =>
      Option(module.drivers).getOrElse(Vector.empty).filter(_ != null).flatMap { driver =>
        collectReferences(driver.value).flatMap { reference =>
          Some(referenceIdValue(reference.id) -> safeLocation(reference.sourceLocation))
        }
      }
    }
    addDuplicates(
      references,
      IrDiagnosticCode.ReferenceIdDuplicate,
      "design-wide reference id",
      Vector("design", "references"),
      diagnostics
    )
  }

  private def validateParameter(
      parameter: Parameter,
      modulePath: Vector[String],
      diagnostics: DiagnosticCollector
  ): Unit = {
    val location = safeLocation(parameter.sourceLocation)
    val path = modulePath :+ "parameters" :+ idText(parameter.id)
    validateLocationContainer(parameter.sourceLocation, path :+ "source", diagnostics)
    if (!nonEmpty(parameter.name)) {
      diagnostics.add(
        IrDiagnosticCode.ParameterNameMissing,
        "parameter name must be non-empty",
        path :+ "name",
        location
      )
    }
    parameter match {
      case value: IntegerParameter => validateIntegerDomain(value, path, diagnostics)
      case value: BooleanParameter => validateBooleanDomain(value, path, diagnostics)
    }
  }

  private def validateIntegerDomain(
      parameter: IntegerParameter,
      path: Vector[String],
      diagnostics: DiagnosticCollector
  ): Unit = {
    val location = safeLocation(parameter.sourceLocation)
    val domain = parameter.domain
    if (domain == null) {
      diagnostics.add(
        IrDiagnosticCode.ParameterDomainMissing,
        s"integer parameter '${parameter.name}' requires a complete bounded domain",
        path :+ "domain",
        location
      )
      return
    }
    val values = requiredVector(
      domain.admittedValues,
      "integer admitted values",
      path :+ "domain" :+ "admitted-values",
      diagnostics,
      location
    )
    if (values.isEmpty) {
      diagnostics.add(
        IrDiagnosticCode.ParameterDomainEmpty,
        s"integer parameter '${parameter.name}' has an empty admitted domain",
        path :+ "domain",
        location
      )
      diagnostics.add(
        IrDiagnosticCode.ParameterDefaultOutsideDomain,
        s"integer parameter '${parameter.name}' default is not admitted by its empty domain",
        path :+ "default",
        location
      )
      return
    }
    if (values.size > MaximumParameterDomainSize) {
      diagnostics.add(
        IrDiagnosticCode.ParameterDomainTooLarge,
        s"integer parameter '${parameter.name}' domain has ${values.size} values; limit is $MaximumParameterDomainSize",
        path :+ "domain",
        location
      )
    }
    if (values.distinct.size != values.size) {
      diagnostics.add(
        IrDiagnosticCode.ParameterDomainDuplicateValue,
        s"integer parameter '${parameter.name}' domain contains duplicate values",
        path :+ "domain",
        location
      )
    }
    if (domain.minimum == null || domain.maximum == null || domain.minimum > domain.maximum) {
      diagnostics.add(
        IrDiagnosticCode.ParameterDomainBoundsInvalid,
        s"integer parameter '${parameter.name}' has invalid retained bounds",
        path :+ "domain",
        location
      )
    } else if (values.exists(value => value == null)) {
      diagnostics.add(
        IrDiagnosticCode.ParameterDomainBoundsInvalid,
        s"integer parameter '${parameter.name}' domain contains a null value",
        path :+ "domain",
        location
      )
    } else {
      val actualMinimum = values.min
      val actualMaximum = values.max
      if (actualMinimum != domain.minimum || actualMaximum != domain.maximum) {
        diagnostics.add(
          IrDiagnosticCode.ParameterDomainBoundsMismatch,
          s"integer parameter '${parameter.name}' retained bounds [${domain.minimum}, ${domain.maximum}] do not equal exact extrema [$actualMinimum, $actualMaximum]",
          path :+ "domain",
          location
        )
      }
    }
    if (parameter.default == null || !values.contains(parameter.default)) {
      diagnostics.add(
        IrDiagnosticCode.ParameterDefaultOutsideDomain,
        s"integer parameter '${parameter.name}' default is not admitted by its exact domain",
        path :+ "default",
        location
      )
    }
  }

  private def validateBooleanDomain(
      parameter: BooleanParameter,
      path: Vector[String],
      diagnostics: DiagnosticCollector
  ): Unit = {
    val location = safeLocation(parameter.sourceLocation)
    val domain = parameter.domain
    if (domain == null) {
      diagnostics.add(
        IrDiagnosticCode.ParameterDomainMissing,
        s"Boolean parameter '${parameter.name}' requires a complete bounded domain",
        path :+ "domain",
        location
      )
      return
    }
    val values = requiredVector(
      domain.admittedValues,
      "Boolean admitted values",
      path :+ "domain" :+ "admitted-values",
      diagnostics,
      location
    )
    if (values.isEmpty) {
      diagnostics.add(
        IrDiagnosticCode.ParameterDomainEmpty,
        s"Boolean parameter '${parameter.name}' has an empty admitted domain",
        path :+ "domain",
        location
      )
    }
    if (values.distinct.size != values.size) {
      diagnostics.add(
        IrDiagnosticCode.ParameterDomainDuplicateValue,
        s"Boolean parameter '${parameter.name}' domain contains duplicate values",
        path :+ "domain",
        location
      )
    }
    if (!values.contains(parameter.default)) {
      diagnostics.add(
        IrDiagnosticCode.ParameterDefaultOutsideDomain,
        s"Boolean parameter '${parameter.name}' default is not admitted by its exact domain",
        path :+ "default",
        location
      )
    }
  }

  private def validateScopes(
      scopes: Vector[Scope],
      scopeById: Map[ScopeId, Scope],
      modulePath: Vector[String],
      diagnostics: DiagnosticCollector
  ): Unit = {
    val roots = scopes.filter(scope => scope.kind == ScopeKind.Module)
    if (
      roots.size != 1 ||
      roots.headOption.exists(scope => scope.parent == null || scope.parent.nonEmpty)
    ) {
      diagnostics.add(
        IrDiagnosticCode.ScopeRootInvalid,
        s"module requires exactly one module scope with no parent, found ${roots.size}",
        modulePath :+ "scopes",
        roots.headOption.flatMap(scope => safeLocation(scope.sourceLocation))
      )
    }
    scopes.foreach { scope =>
      val path = modulePath :+ "scopes" :+ idText(scope.id)
      val location = safeLocation(scope.sourceLocation)
      validateLocationContainer(scope.sourceLocation, path :+ "source", diagnostics)
      if (scope.kind == null) {
        diagnostics.add(
          IrDiagnosticCode.ScopeKindMissing,
          "scope kind must be known",
          path :+ "kind",
          location
        )
      }
      if (scope.parent == null) {
        diagnostics.add(
          IrDiagnosticCode.LocationContainerMissing,
          "scope parent option must not be null",
          path :+ "parent",
          location
        )
      } else {
        if (scope.kind != null && scope.kind != ScopeKind.Module && scope.parent.isEmpty) {
          diagnostics.add(
            IrDiagnosticCode.ScopeParentRequired,
            s"non-module scope '${idText(scope.id)}' requires a lexical parent",
            path :+ "parent",
            location
          )
        }
        scope.parent.foreach { parent =>
          if (!validScopeId(parent) || !scopeById.contains(parent)) {
            diagnostics.add(
              IrDiagnosticCode.ScopeParentUnresolved,
              s"scope parent '${idText(parent)}' is not declared in this module",
              path :+ "parent",
              location
            )
          }
        }
      }
      if (scope.label == null || scope.label.exists(value => !nonEmpty(value))) {
        diagnostics.add(
          IrDiagnosticCode.NameOriginValueInvalid,
          "scope label option must be non-null and contain no empty label",
          path :+ "label",
          location
        )
      }
    }

    scopes.foreach { start =>
      if (validScopeId(start.id)) {
        var current: Option[ScopeId] = Some(start.id)
        var visited = Set.empty[ScopeId]
        var cycle = false
        while (current.nonEmpty && !cycle) {
          val id = current.get
          if (visited.contains(id)) cycle = true
          else {
            visited += id
            current = scopeById
              .get(id)
              .flatMap(scope => Option(scope.parent).flatten.filter(validScopeId))
          }
        }
        if (cycle) {
          diagnostics.add(
            IrDiagnosticCode.ScopeCycle,
            s"scope '${idText(start.id)}' participates in a parent cycle",
            modulePath :+ "scopes" :+ idText(start.id),
            safeLocation(start.sourceLocation)
          )
        }
      }
    }
  }

  private def validateGenerateIndices(
      indices: Vector[GenerateIndex],
      scopeById: Map[ScopeId, Scope],
      modulePath: Vector[String],
      diagnostics: DiagnosticCollector
  ): Unit = indices.foreach { index =>
    val path = modulePath :+ "generate-indices" :+ idText(index.id)
    val location = safeLocation(index.sourceLocation)
    validateLocationContainer(index.sourceLocation, path :+ "source", diagnostics)
    if (!validScopeId(index.owner) || !scopeById.contains(index.owner)) {
      diagnostics.add(
        IrDiagnosticCode.GenerateIndexOwnerUnresolved,
        s"generate index owner '${idText(index.owner)}' is not declared in this module",
        path :+ "owner",
        location
      )
    } else if (scopeById(index.owner).kind != ScopeKind.Generate) {
      diagnostics.add(
        IrDiagnosticCode.GenerateIndexOwnerKindInvalid,
        s"generate index owner '${idText(index.owner)}' must be a generate scope",
        path :+ "owner",
        location
      )
    }
    if (!nonEmpty(index.name)) {
      diagnostics.add(
        IrDiagnosticCode.GenerateIndexNameMissing,
        "generate index name must be non-empty",
        path :+ "name",
        location
      )
    }
    if (index.minimum == null || index.maximum == null || index.minimum > index.maximum) {
      diagnostics.add(
        IrDiagnosticCode.GenerateIndexDomainInvalid,
        "generate index requires finite ordered bounds",
        path :+ "domain",
        location
      )
    }
  }

  private def validateDeclaration(
      declaration: Declaration,
      scopeById: Map[ScopeId, Scope],
      integerParameters: Map[ParameterId, IntegerParameter],
      booleanParameters: Map[ParameterId, BooleanParameter],
      indices: Map[GenerateIndexId, GenerateIndex],
      modulePath: Vector[String],
      diagnostics: DiagnosticCollector
  ): Unit = {
    val path = modulePath :+ "declarations" :+ idText(declaration.id)
    val location = safeLocation(declaration.sourceLocation)
    validateLocationContainer(declaration.sourceLocation, path :+ "source", diagnostics)
    if (!validScopeId(declaration.owner) || !scopeById.contains(declaration.owner)) {
      diagnostics.add(
        IrDiagnosticCode.DeclarationScopeUnresolved,
        s"declaration owner '${idText(declaration.owner)}' is not declared in this module",
        path :+ "owner",
        location
      )
    }
    if (declaration.kind == null) {
      diagnostics.add(
        IrDiagnosticCode.DeclarationKindMissing,
        "declaration kind must be known",
        path :+ "kind",
        location
      )
    } else declaration.kind match {
      case DeclarationKind.Port(direction) if direction == null =>
        diagnostics.add(
          IrDiagnosticCode.PortDirectionMissing,
          "port direction must be known",
          path :+ "kind" :+ "direction",
          location
        )
      case _ =>
    }

    if (declaration.packedType == null) {
      diagnostics.add(
        IrDiagnosticCode.PackedTypeMissing,
        "packed-type option must not be null",
        path :+ "packed-type",
        location
      )
    }
    val packedType = Option(declaration.packedType).flatten
    if (declaration.kind != null && declaration.kind.requiresPackedType && packedType.isEmpty) {
      diagnostics.add(
        IrDiagnosticCode.PackedTypeMissing,
        s"declaration kind '${declaration.kind.label}' requires complete packed-type metadata",
        path :+ "packed-type",
        location
      )
    }
    packedType.foreach { packed =>
      validatePackedType(
        packed,
        scopeById,
        declaration.owner,
        integerParameters,
        booleanParameters,
        indices,
        path :+ "packed-type",
        location,
        diagnostics
      )
    }

    if (declaration.nameOrigin == null) {
      diagnostics.add(
        IrDiagnosticCode.NameOriginMissing,
        "declaration name-origin metadata must be present",
        path :+ "name-origin",
        location
      )
    } else declaration.nameOrigin match {
      case NameOrigin.Unknown =>
        diagnostics.add(
          IrDiagnosticCode.NameOriginUnknown,
          "declaration name origin must be proven before canonical publication",
          path :+ "name-origin",
          location
        )
      case NameOrigin.Explicit(value) if !nonEmpty(value) =>
        diagnostics.add(
          IrDiagnosticCode.NameOriginValueInvalid,
          "explicit declaration name must be non-empty",
          path :+ "name-origin",
          location
        )
      case NameOrigin.Reflected(value) if !nonEmpty(value) =>
        diagnostics.add(
          IrDiagnosticCode.NameOriginValueInvalid,
          "reflected declaration name must be non-empty",
          path :+ "name-origin",
          location
        )
      case _ =>
    }

    if (declaration.observability == null) {
      diagnostics.add(
        IrDiagnosticCode.ObservabilityMissing,
        "declaration observability metadata must be present",
        path :+ "observability",
        location
      )
    } else if (!declaration.observability.complete) {
      diagnostics.add(
        IrDiagnosticCode.ObservabilityIncomplete,
        "declaration observability metadata is incomplete",
        path :+ "observability",
        location
      )
    }

    validateAttributes(declaration.attributes, path, location, diagnostics)
    validateComments(declaration.comments, path, location, diagnostics)
  }

  private def validateAttributes(
      values: Vector[IrAttribute],
      declarationPath: Vector[String],
      ownerLocation: Option[SourceLocation],
      diagnostics: DiagnosticCollector
  ): Unit = {
    val attributes = requiredVector(
      values,
      "IR attributes",
      declarationPath :+ "attributes",
      diagnostics,
      ownerLocation
    )
    attributes.zipWithIndex.foreach { case (attribute, index) =>
      val path = declarationPath :+ "attributes" :+ index.toString
      if (attribute == null) {
        diagnostics.add(
          IrDiagnosticCode.AttributeMissing,
          "attribute entry must not be null",
          path,
          ownerLocation
        )
      } else {
        val location = safeLocation(attribute.sourceLocation).orElse(ownerLocation)
        validateLocationContainer(attribute.sourceLocation, path :+ "source", diagnostics)
        if (!nonEmpty(attribute.name)) {
          diagnostics.add(
            IrDiagnosticCode.AttributeNameMissing,
            "attribute name must be non-empty",
            path :+ "name",
            location
          )
        }
        if (attribute.value == null || attribute.value.exists(_ == null)) {
          diagnostics.add(
            IrDiagnosticCode.AttributeValueMissing,
            "attribute value option must be non-null and contain no null value",
            path :+ "value",
            location
          )
        }
        if (attribute.kind == null) {
          diagnostics.add(
            IrDiagnosticCode.AttributeKindMissing,
            "attribute kind must be known",
            path :+ "kind",
            location
          )
        }
      }
    }
  }

  private def validateComments(
      values: Vector[IrComment],
      declarationPath: Vector[String],
      ownerLocation: Option[SourceLocation],
      diagnostics: DiagnosticCollector
  ): Unit = {
    val comments = requiredVector(
      values,
      "IR comments",
      declarationPath :+ "comments",
      diagnostics,
      ownerLocation
    )
    comments.zipWithIndex.foreach { case (comment, index) =>
      val path = declarationPath :+ "comments" :+ index.toString
      if (comment == null) {
        diagnostics.add(
          IrDiagnosticCode.CommentMissing,
          "comment entry must not be null",
          path,
          ownerLocation
        )
      } else {
        val location = safeLocation(comment.sourceLocation).orElse(ownerLocation)
        validateLocationContainer(comment.sourceLocation, path :+ "source", diagnostics)
        if (!nonEmpty(comment.text)) {
          diagnostics.add(
            IrDiagnosticCode.CommentTextMissing,
            "comment text must be non-empty",
            path :+ "text",
            location
          )
        }
      }
    }
  }

  private def validateDriver(
      driver: Driver,
      scopeById: Map[ScopeId, Scope],
      declarations: Map[SymbolId, Declaration],
      integerParameters: Map[ParameterId, IntegerParameter],
      booleanParameters: Map[ParameterId, BooleanParameter],
      indices: Map[GenerateIndexId, GenerateIndex],
      modulePath: Vector[String],
      diagnostics: DiagnosticCollector
  ): Unit = {
    val path = modulePath :+ "drivers" :+ idText(driver.id)
    val location = safeLocation(driver.sourceLocation)
    validateLocationContainer(driver.sourceLocation, path :+ "source", diagnostics)
    val ownerResolved =
      validScopeId(driver.owner) && scopeById.contains(driver.owner)
    if (!ownerResolved) {
      diagnostics.add(
        IrDiagnosticCode.DriverScopeUnresolved,
        s"driver owner '${idText(driver.owner)}' is not declared in this module",
        path :+ "owner",
        location
      )
    }
    val targetResolved =
      validSymbolId(driver.target) && declarations.contains(driver.target)
    if (!targetResolved) {
      diagnostics.add(
        IrDiagnosticCode.DriverTargetUnresolved,
        s"driver target '${idText(driver.target)}' is not declared in this module",
        path :+ "target",
        location
      )
    } else if (
      ownerResolved &&
      !scopeIsAncestor(scopeById, declarations(driver.target).owner, driver.owner)
    ) {
      diagnostics.add(
        IrDiagnosticCode.DriverTargetNotVisible,
        s"driver target '${idText(driver.target)}' is outside owner scope '${idText(driver.owner)}'",
        path :+ "target",
        location
      )
    }
    if (driver.kind == null) {
      diagnostics.add(
        IrDiagnosticCode.DriverKindMissing,
        "driver kind must be known",
        path :+ "kind",
        location
      )
    }
    if (driver.coverage == null) {
      diagnostics.add(
        IrDiagnosticCode.DriverCoverageMissing,
        "driver coverage metadata must be present",
        path :+ "coverage",
        location
      )
    } else if (driver.coverage == DriverCoverage.Unknown) {
      diagnostics.add(
        IrDiagnosticCode.DriverCoverageUnknown,
        "driver coverage must be proven before canonical publication",
        path :+ "coverage",
        location
      )
    }
    validateRtlExpr(
      driver.value,
      scopeById,
      driver.owner,
      declarations,
      integerParameters,
      booleanParameters,
      indices,
      path :+ "value",
      location,
      diagnostics
    )
    validateAttributes(driver.attributes, path, location, diagnostics)
    validateComments(driver.comments, path, location, diagnostics)
  }

  private def validatePackedType(
      packedType: PackedType,
      scopeById: Map[ScopeId, Scope],
      owner: ScopeId,
      integerParameters: Map[ParameterId, IntegerParameter],
      booleanParameters: Map[ParameterId, BooleanParameter],
      indices: Map[GenerateIndexId, GenerateIndex],
      path: Vector[String],
      location: Option[SourceLocation],
      diagnostics: DiagnosticCollector
  ): Unit = {
    if (packedType == null) {
      diagnostics.add(
        IrDiagnosticCode.PackedTypeMissing,
        "packed type must not be null",
        path,
        location
      )
      return
    }
    if (packedType.signedness == null) {
      diagnostics.add(
        IrDiagnosticCode.PackedSignednessMissing,
        "packed type signedness must be known",
        path :+ "signedness",
        location
      )
    }
    if (packedType.valueSemantics == null) {
      diagnostics.add(
        IrDiagnosticCode.PackedValueSemanticsMissing,
        "packed type value semantics must be known",
        path :+ "value-semantics",
        location
      )
    } else {
      val consistent = packedType.valueSemantics match {
        case PackedValueSemantics.SignedInteger =>
          packedType.signedness == Signedness.Signed
        case PackedValueSemantics.UnsignedInteger | PackedValueSemantics.Boolean =>
          packedType.signedness == Signedness.Unsigned
        case PackedValueSemantics.BitVector => true
      }
      if (!consistent) {
        diagnostics.add(
          IrDiagnosticCode.PackedValueSemanticsMismatch,
          "packed value semantics and signedness are inconsistent",
          path :+ "value-semantics",
          location
        )
      }
    }
    val interval = validateIntExpr(
      packedType.width,
      scopeById,
      owner,
      integerParameters,
      booleanParameters,
      indices,
      path :+ "width",
      location,
      diagnostics
    )
    interval.foreach { value =>
      if (value.minimum < 1) {
        diagnostics.add(
          IrDiagnosticCode.PackedWidthNotPositive,
          s"packed width is not proven positive over its complete domain [${value.minimum}, ${value.maximum}]",
          path :+ "width",
          location
        )
      }
      if (
        packedType.valueSemantics == PackedValueSemantics.Boolean &&
        (value.minimum != 1 || value.maximum != 1)
      ) {
        diagnostics.add(
          IrDiagnosticCode.PackedValueSemanticsMismatch,
          s"Boolean packed value semantics require width one, observed [${value.minimum}, ${value.maximum}]",
          path :+ "value-semantics",
          location
        )
      }
    }
  }

  private def validateRtlExpr(
      expression: RtlExpr,
      scopeById: Map[ScopeId, Scope],
      expectedOwner: ScopeId,
      declarations: Map[SymbolId, Declaration],
      integerParameters: Map[ParameterId, IntegerParameter],
      booleanParameters: Map[ParameterId, BooleanParameter],
      indices: Map[GenerateIndexId, GenerateIndex],
      path: Vector[String],
      location: Option[SourceLocation],
      diagnostics: DiagnosticCollector
  ): Unit = {
    if (expression == null) {
      diagnostics.add(
        IrDiagnosticCode.RtlExpressionMissing,
        "runtime expression must not be null",
        path,
        location
      )
      return
    }
    expression match {
      case RtlExpr.Ref(id, target, owner, sourceLocation) =>
        val referenceLocation = safeLocation(sourceLocation).orElse(location)
        validateLocationContainer(sourceLocation, path :+ "source", diagnostics)
        validateIdentifier(
          referenceIdValue(id),
          IrDiagnosticCode.ReferenceIdMissing,
          IrDiagnosticCode.ReferenceIdInvalid,
          "reference id",
          path :+ "id",
          referenceLocation,
          diagnostics
        )
        val ownerResolved = validScopeId(owner) && scopeById.contains(owner)
        if (!ownerResolved) {
          diagnostics.add(
            IrDiagnosticCode.ReferenceOwnerUnresolved,
            s"reference owner '${idText(owner)}' is not declared in this module",
            path :+ "owner",
            referenceLocation
          )
        } else if (validScopeId(expectedOwner) && owner != expectedOwner) {
          diagnostics.add(
            IrDiagnosticCode.ReferenceOwnerMismatch,
            s"reference owner '${idText(owner)}' does not match driver owner '${idText(expectedOwner)}'",
            path :+ "owner",
            referenceLocation
          )
        }
        val targetResolved = validSymbolId(target) && declarations.contains(target)
        if (!targetResolved) {
          diagnostics.add(
            IrDiagnosticCode.RtlReferenceUnresolved,
            s"runtime reference target '${idText(target)}' is not declared in this module",
            path,
            referenceLocation
          )
        } else if (
          ownerResolved &&
          !scopeIsAncestor(scopeById, declarations(target).owner, owner)
        ) {
          diagnostics.add(
            IrDiagnosticCode.RtlReferenceNotVisible,
            s"runtime reference target '${idText(target)}' is outside owner scope '${idText(owner)}'",
            path,
            referenceLocation
          )
        }
      case RtlExpr.Literal(value, width, _) =>
        if (value == null || width < 1) {
          diagnostics.add(
            IrDiagnosticCode.RtlLiteralInvalid,
            "runtime literal requires a non-null value and positive width",
            path,
            location
          )
        }
      case RtlExpr.Unary(operator, value) =>
        if (operator == null) missingRtlOperator(path, location, diagnostics)
        validateRtlExpr(value, scopeById, expectedOwner, declarations, integerParameters, booleanParameters, indices, path :+ "value", location, diagnostics)
      case RtlExpr.Binary(operator, left, right) =>
        if (operator == null) missingRtlOperator(path, location, diagnostics)
        validateRtlExpr(left, scopeById, expectedOwner, declarations, integerParameters, booleanParameters, indices, path :+ "left", location, diagnostics)
        validateRtlExpr(right, scopeById, expectedOwner, declarations, integerParameters, booleanParameters, indices, path :+ "right", location, diagnostics)
      case RtlExpr.Mux(condition, yes, no) =>
        validateRtlExpr(condition, scopeById, expectedOwner, declarations, integerParameters, booleanParameters, indices, path :+ "condition", location, diagnostics)
        validateRtlExpr(yes, scopeById, expectedOwner, declarations, integerParameters, booleanParameters, indices, path :+ "when-true", location, diagnostics)
        validateRtlExpr(no, scopeById, expectedOwner, declarations, integerParameters, booleanParameters, indices, path :+ "when-false", location, diagnostics)
      case RtlExpr.Concat(values) =>
        val parts = requiredVector(values, "concatenation operands", path, diagnostics, location)
        if (parts.isEmpty) {
          diagnostics.add(
            IrDiagnosticCode.RtlConcatEmpty,
            "runtime concatenation must contain at least one operand",
            path,
            location
          )
        }
        parts.zipWithIndex.foreach { case (value, index) =>
          validateRtlExpr(value, scopeById, expectedOwner, declarations, integerParameters, booleanParameters, indices, path :+ index.toString, location, diagnostics)
        }
      case RtlExpr.BitSelect(value, index) =>
        validateRtlExpr(value, scopeById, expectedOwner, declarations, integerParameters, booleanParameters, indices, path :+ "value", location, diagnostics)
        validateRtlExpr(index, scopeById, expectedOwner, declarations, integerParameters, booleanParameters, indices, path :+ "index", location, diagnostics)
      case RtlExpr.PartSelect(value, offset, width) =>
        validateRtlExpr(value, scopeById, expectedOwner, declarations, integerParameters, booleanParameters, indices, path :+ "value", location, diagnostics)
        validateIntExpr(offset, scopeById, expectedOwner, integerParameters, booleanParameters, indices, path :+ "offset", location, diagnostics).foreach { interval =>
          if (interval.minimum < 0) {
            diagnostics.add(
              IrDiagnosticCode.PartSelectOffsetInvalid,
              s"part-select offset may be negative over [${interval.minimum}, ${interval.maximum}]",
              path :+ "offset",
              location
            )
          }
        }
        validateIntExpr(width, scopeById, expectedOwner, integerParameters, booleanParameters, indices, path :+ "width", location, diagnostics).foreach { interval =>
          if (interval.minimum < 1) {
            diagnostics.add(
              IrDiagnosticCode.PartSelectWidthInvalid,
              s"part-select width is not positive over [${interval.minimum}, ${interval.maximum}]",
              path :+ "width",
              location
            )
          }
        }
      case RtlExpr.Resize(value, width, signedness) =>
        validateRtlExpr(value, scopeById, expectedOwner, declarations, integerParameters, booleanParameters, indices, path :+ "value", location, diagnostics)
        if (signedness == null) {
          diagnostics.add(
            IrDiagnosticCode.PackedSignednessMissing,
            "resize signedness must be known",
            path :+ "signedness",
            location
          )
        }
        validateIntExpr(width, scopeById, expectedOwner, integerParameters, booleanParameters, indices, path :+ "width", location, diagnostics).foreach { interval =>
          if (interval.minimum < 1) {
            diagnostics.add(
              IrDiagnosticCode.ResizeWidthInvalid,
              s"resize width is not positive over [${interval.minimum}, ${interval.maximum}]",
              path :+ "width",
              location
            )
          }
        }
      case RtlExpr.Cast(value, signedness) =>
        validateRtlExpr(value, scopeById, expectedOwner, declarations, integerParameters, booleanParameters, indices, path :+ "value", location, diagnostics)
        if (signedness == null) {
          diagnostics.add(
            IrDiagnosticCode.PackedSignednessMissing,
            "cast signedness must be known",
            path :+ "signedness",
            location
          )
        }
    }
  }

  private def validateIntExpr(
      expression: IntExpr,
      scopeById: Map[ScopeId, Scope],
      owner: ScopeId,
      integerParameters: Map[ParameterId, IntegerParameter],
      booleanParameters: Map[ParameterId, BooleanParameter],
      indices: Map[GenerateIndexId, GenerateIndex],
      path: Vector[String],
      location: Option[SourceLocation],
      diagnostics: DiagnosticCollector,
      evaluateSemantics: Boolean = true
  ): Option[IntInterval] = {
    if (expression == null) {
      diagnostics.add(
        IrDiagnosticCode.IntExpressionMissing,
        "integer expression must not be null",
        path,
        location
      )
      return None
    }

    def child(value: IntExpr, suffix: String): Boolean =
      validateIntExpr(
        value,
        scopeById,
        owner,
        integerParameters,
        booleanParameters,
        indices,
        path :+ suffix,
        location,
        diagnostics,
        evaluateSemantics = false
      ).nonEmpty

    def children(left: IntExpr, right: IntExpr): Boolean = {
      val leftValid = child(left, "left")
      val rightValid = child(right, "right")
      leftValid && rightValid
    }

    val structurallyValid = expression match {
      case IntExpr.Literal(value) =>
        if (value == null) {
          diagnostics.add(
            IrDiagnosticCode.IntegerLiteralMissing,
            "integer literal value must not be null",
            path,
            location
          )
          false
        } else true
      case IntExpr.ParameterRef(parameter) =>
        if (!validParameterId(parameter)) {
          unresolvedParameter("<null>", path, location, diagnostics)
          false
        } else integerParameters.get(parameter) match {
          case Some(_) => true
          case None if booleanParameters.contains(parameter) =>
            diagnostics.add(
              IrDiagnosticCode.ParameterKindMismatch,
              s"Boolean parameter '${idText(parameter)}' cannot be used as an integer expression",
              path,
              location
            )
            false
          case None =>
            unresolvedParameter(idText(parameter), path, location, diagnostics)
            false
        }
      case IntExpr.GenerateIndexRef(index) =>
        if (!validGenerateIndexId(index) || !indices.contains(index)) {
          diagnostics.add(
            IrDiagnosticCode.GenerateIndexUnresolved,
            s"generate index '${idText(index)}' is not declared in this module",
            path,
            location
          )
          false
        } else if (!scopeIsAncestor(scopeById, indices(index).owner, owner)) {
          diagnostics.add(
            IrDiagnosticCode.GenerateIndexNotVisible,
            s"generate index '${idText(index)}' is outside owner scope '${idText(owner)}'",
            path,
            location
          )
          false
        } else true
      case IntExpr.Negate(value)              => child(value, "value")
      case IntExpr.Add(left, right)           => children(left, right)
      case IntExpr.Subtract(left, right)      => children(left, right)
      case IntExpr.Multiply(left, right)      => children(left, right)
      case IntExpr.Divide(left, right)        => children(left, right)
      case IntExpr.Modulo(left, right)        => children(left, right)
      case IntExpr.Min(left, right)           => children(left, right)
      case IntExpr.Max(left, right)           => children(left, right)
      case IntExpr.Select(condition, yes, no) =>
        val conditionValid = validateBoolExpr(
          condition,
          scopeById,
          owner,
          integerParameters,
          booleanParameters,
          indices,
          path :+ "condition",
          location,
          diagnostics
        )
        val yesValid = child(yes, "when-true")
        val noValid = child(no, "when-false")
        conditionValid && yesValid && noValid
      case IntExpr.CeilLog2(value)     => child(value, "value")
      case IntExpr.AddressWidth(value) => child(value, "value")
      case IntExpr.Pow2(value)         => child(value, "exponent")
    }

    if (!structurallyValid) None
    else if (!evaluateSemantics) Some(IntInterval(BigInt(0), BigInt(0)))
    else
      exactIntInterval(
        expression,
        integerParameters,
        booleanParameters,
        indices,
        path,
        location,
        diagnostics
      )
  }

  private def validateBoolExpr(
      expression: BoolExpr,
      scopeById: Map[ScopeId, Scope],
      owner: ScopeId,
      integerParameters: Map[ParameterId, IntegerParameter],
      booleanParameters: Map[ParameterId, BooleanParameter],
      indices: Map[GenerateIndexId, GenerateIndex],
      path: Vector[String],
      location: Option[SourceLocation],
      diagnostics: DiagnosticCollector
  ): Boolean = {
    if (expression == null) {
      diagnostics.add(
        IrDiagnosticCode.BoolExpressionMissing,
        "Boolean expression must not be null",
        path,
        location
      )
      return false
    }

    def integer(value: IntExpr, suffix: String): Boolean =
      validateIntExpr(
        value,
        scopeById,
        owner,
        integerParameters,
        booleanParameters,
        indices,
        path :+ suffix,
        location,
        diagnostics,
        evaluateSemantics = false
      ).nonEmpty

    def integers(left: IntExpr, right: IntExpr): Boolean = {
      val leftValid = integer(left, "left")
      val rightValid = integer(right, "right")
      leftValid && rightValid
    }

    expression match {
      case BoolExpr.Literal(_) => true
      case BoolExpr.ParameterRef(parameter) =>
        if (!validParameterId(parameter)) {
          unresolvedParameter("<null>", path, location, diagnostics)
          false
        } else if (integerParameters.contains(parameter)) {
          diagnostics.add(
            IrDiagnosticCode.ParameterKindMismatch,
            s"integer parameter '${idText(parameter)}' cannot be used as a Boolean expression",
            path,
            location
          )
          false
        } else if (!booleanParameters.contains(parameter)) {
          unresolvedParameter(idText(parameter), path, location, diagnostics)
          false
        } else true
      case BoolExpr.LessThan(left, right)           => integers(left, right)
      case BoolExpr.LessThanOrEqual(left, right)    => integers(left, right)
      case BoolExpr.GreaterThan(left, right)        => integers(left, right)
      case BoolExpr.GreaterThanOrEqual(left, right) => integers(left, right)
      case BoolExpr.Equal(left, right)              => integers(left, right)
      case BoolExpr.NotEqual(left, right)           => integers(left, right)
      case BoolExpr.IsPow2(value)                   => integer(value, "value")
      case BoolExpr.Not(value) =>
        validateBoolExpr(value, scopeById, owner, integerParameters, booleanParameters, indices, path :+ "value", location, diagnostics)
      case BoolExpr.And(left, right) =>
        val leftValid = validateBoolExpr(left, scopeById, owner, integerParameters, booleanParameters, indices, path :+ "left", location, diagnostics)
        val rightValid = validateBoolExpr(right, scopeById, owner, integerParameters, booleanParameters, indices, path :+ "right", location, diagnostics)
        leftValid && rightValid
      case BoolExpr.Or(left, right) =>
        val leftValid = validateBoolExpr(left, scopeById, owner, integerParameters, booleanParameters, indices, path :+ "left", location, diagnostics)
        val rightValid = validateBoolExpr(right, scopeById, owner, integerParameters, booleanParameters, indices, path :+ "right", location, diagnostics)
        leftValid && rightValid
    }
  }

  private sealed trait ExactVariable {
    def cardinality: BigInt
  }

  private final case class ExactIntegerVariable(
      id: ParameterId,
      values: Vector[BigInt]
  ) extends ExactVariable {
    override def cardinality: BigInt = BigInt(values.size)
  }

  private final case class ExactBooleanVariable(
      id: ParameterId,
      values: Vector[Boolean]
  ) extends ExactVariable {
    override def cardinality: BigInt = BigInt(values.size)
  }

  private final case class ExactGenerateVariable(
      id: GenerateIndexId,
      minimum: BigInt,
      maximum: BigInt
  ) extends ExactVariable {
    override def cardinality: BigInt = maximum - minimum + 1
  }

  private sealed trait ExactEvaluationFailure
  private case object ExactEvaluationUnavailable extends ExactEvaluationFailure
  private final case class ExactSemanticFailure(
      code: String,
      message: String,
      pathSuffix: Vector[String] = Vector.empty
  ) extends ExactEvaluationFailure

  private def exactIntInterval(
      expression: IntExpr,
      integerParameters: Map[ParameterId, IntegerParameter],
      booleanParameters: Map[ParameterId, BooleanParameter],
      indices: Map[GenerateIndexId, GenerateIndex],
      path: Vector[String],
      location: Option[SourceLocation],
      diagnostics: DiagnosticCollector
  ): Option[IntInterval] = {
    val variables = exactVariables(expression, integerParameters, booleanParameters, indices)
    if (variables.exists(_.cardinality <= 0)) return None

    var requiredCases = BigInt(1)
    variables.foreach { variable =>
      if (requiredCases <= MaximumExactEvaluationCases) {
        requiredCases *= variable.cardinality
      }
    }
    if (requiredCases > MaximumExactEvaluationCases) {
      diagnostics.add(
        IrDiagnosticCode.ExactEvaluationLimitReached,
        s"exact expression evaluation requires more than $MaximumExactEvaluationCases admitted assignments",
        path,
        location
      )
      return None
    }

    val integerValues = mutable.Map.empty[ParameterId, BigInt]
    val booleanValues = mutable.Map.empty[ParameterId, Boolean]
    val generateValues = mutable.Map.empty[GenerateIndexId, BigInt]
    var minimum: BigInt = null
    var maximum: BigInt = null
    var failure: ExactEvaluationFailure = null

    def record(value: BigInt): Unit = {
      if (minimum == null || value < minimum) minimum = value
      if (maximum == null || value > maximum) maximum = value
    }

    def evaluateAssignment(): Unit = {
      evaluateInt(expression, integerValues, booleanValues, generateValues) match {
        case Right(value)  => record(value)
        case Left(problem) => failure = problem
      }
    }

    def enumerate(position: Int): Unit = {
      if (failure != null) return
      if (position == variables.size) {
        evaluateAssignment()
      } else variables(position) match {
        case ExactIntegerVariable(id, values) =>
          var index = 0
          while (index < values.size && failure == null) {
            integerValues.update(id, values(index))
            enumerate(position + 1)
            index += 1
          }
          integerValues.remove(id)
        case ExactBooleanVariable(id, values) =>
          var index = 0
          while (index < values.size && failure == null) {
            booleanValues.update(id, values(index))
            enumerate(position + 1)
            index += 1
          }
          booleanValues.remove(id)
        case ExactGenerateVariable(id, lower, upper) =>
          var value = lower
          while (value <= upper && failure == null) {
            generateValues.update(id, value)
            enumerate(position + 1)
            value += 1
          }
          generateValues.remove(id)
      }
    }

    enumerate(0)
    failure match {
      case problem: ExactSemanticFailure =>
        diagnostics.add(
          problem.code,
          problem.message,
          path ++ problem.pathSuffix,
          location
        )
        None
      case ExactEvaluationUnavailable => None
      case null if minimum != null && maximum != null =>
        Some(IntInterval(minimum, maximum))
      case _ => None
    }
  }

  private def exactVariables(
      expression: IntExpr,
      integerParameters: Map[ParameterId, IntegerParameter],
      booleanParameters: Map[ParameterId, BooleanParameter],
      indices: Map[GenerateIndexId, GenerateIndex]
  ): Vector[ExactVariable] = {
    val integerIds = mutable.Set.empty[ParameterId]
    val booleanIds = mutable.Set.empty[ParameterId]
    val generateIds = mutable.Set.empty[GenerateIndexId]

    def collectInt(value: IntExpr): Unit = if (value != null) value match {
      case IntExpr.Literal(_) =>
      case IntExpr.ParameterRef(parameter) =>
        if (validParameterId(parameter) && integerParameters.contains(parameter))
          integerIds += parameter
      case IntExpr.GenerateIndexRef(index) =>
        if (validGenerateIndexId(index) && indices.contains(index))
          generateIds += index
      case IntExpr.Negate(inner) => collectInt(inner)
      case IntExpr.Add(left, right) => collectInt(left); collectInt(right)
      case IntExpr.Subtract(left, right) => collectInt(left); collectInt(right)
      case IntExpr.Multiply(left, right) => collectInt(left); collectInt(right)
      case IntExpr.Divide(left, right) => collectInt(left); collectInt(right)
      case IntExpr.Modulo(left, right) => collectInt(left); collectInt(right)
      case IntExpr.Min(left, right) => collectInt(left); collectInt(right)
      case IntExpr.Max(left, right) => collectInt(left); collectInt(right)
      case IntExpr.Select(condition, yes, no) =>
        collectBool(condition); collectInt(yes); collectInt(no)
      case IntExpr.CeilLog2(inner) => collectInt(inner)
      case IntExpr.AddressWidth(inner) => collectInt(inner)
      case IntExpr.Pow2(inner) => collectInt(inner)
    }

    def collectBool(value: BoolExpr): Unit = if (value != null) value match {
      case BoolExpr.Literal(_) =>
      case BoolExpr.ParameterRef(parameter) =>
        if (validParameterId(parameter) && booleanParameters.contains(parameter))
          booleanIds += parameter
      case BoolExpr.LessThan(left, right) => collectInt(left); collectInt(right)
      case BoolExpr.LessThanOrEqual(left, right) => collectInt(left); collectInt(right)
      case BoolExpr.GreaterThan(left, right) => collectInt(left); collectInt(right)
      case BoolExpr.GreaterThanOrEqual(left, right) => collectInt(left); collectInt(right)
      case BoolExpr.Equal(left, right) => collectInt(left); collectInt(right)
      case BoolExpr.NotEqual(left, right) => collectInt(left); collectInt(right)
      case BoolExpr.IsPow2(inner) => collectInt(inner)
      case BoolExpr.Not(inner) => collectBool(inner)
      case BoolExpr.And(left, right) => collectBool(left); collectBool(right)
      case BoolExpr.Or(left, right) => collectBool(left); collectBool(right)
    }

    collectInt(expression)
    val values = Vector.newBuilder[ExactVariable]
    integerIds.toVector.sortBy(id => Option(id.value).getOrElse("")).foreach { id =>
      validIntegerValues(integerParameters(id)).foreach { domain =>
        values += ExactIntegerVariable(id, domain)
      }
    }
    booleanIds.toVector.sortBy(id => Option(id.value).getOrElse("")).foreach { id =>
      validBooleanValues(booleanParameters(id)).foreach { domain =>
        values += ExactBooleanVariable(id, domain)
      }
    }
    generateIds.toVector.sortBy(id => Option(id.value).getOrElse("")).foreach { id =>
      val index = indices(id)
      if (
        index.minimum != null && index.maximum != null &&
        index.minimum <= index.maximum
      ) {
        values += ExactGenerateVariable(id, index.minimum, index.maximum)
      }
    }
    values.result()
  }

  private def validIntegerValues(
      parameter: IntegerParameter
  ): Option[Vector[BigInt]] = Option(parameter.domain).flatMap { domain =>
    Option(domain.admittedValues).filter { values =>
      values.nonEmpty && values.size <= MaximumParameterDomainSize &&
      !values.exists(_ == null) && values.distinct.size == values.size &&
      domain.minimum != null && domain.maximum != null &&
      values.min == domain.minimum && values.max == domain.maximum &&
      values.contains(parameter.default)
    }
  }

  private def validBooleanValues(
      parameter: BooleanParameter
  ): Option[Vector[Boolean]] = Option(parameter.domain).flatMap { domain =>
    Option(domain.admittedValues).filter { values =>
      values.nonEmpty && values.distinct.size == values.size &&
      values.contains(parameter.default)
    }
  }

  private def evaluateInt(
      expression: IntExpr,
      integerValues: mutable.Map[ParameterId, BigInt],
      booleanValues: mutable.Map[ParameterId, Boolean],
      generateValues: mutable.Map[GenerateIndexId, BigInt],
      relativePath: Vector[String] = Vector.empty
  ): Either[ExactEvaluationFailure, BigInt] = {
    def binary(
        left: IntExpr,
        right: IntExpr
    )(operation: (BigInt, BigInt) => Either[ExactEvaluationFailure, BigInt]) =
      evaluateInt(
        left,
        integerValues,
        booleanValues,
        generateValues,
        relativePath :+ "left"
      ) match {
        case Left(problem) => Left(problem)
        case Right(leftValue) =>
          evaluateInt(
            right,
            integerValues,
            booleanValues,
            generateValues,
            relativePath :+ "right"
          ) match {
            case Left(problem)     => Left(problem)
            case Right(rightValue) => operation(leftValue, rightValue)
          }
      }

    expression match {
      case IntExpr.Literal(value) => Right(value)
      case IntExpr.ParameterRef(parameter) =>
        if (validParameterId(parameter))
          integerValues.get(parameter).toRight(ExactEvaluationUnavailable)
        else Left(ExactEvaluationUnavailable)
      case IntExpr.GenerateIndexRef(index) =>
        if (validGenerateIndexId(index))
          generateValues.get(index).toRight(ExactEvaluationUnavailable)
        else Left(ExactEvaluationUnavailable)
      case IntExpr.Negate(value) =>
        evaluateInt(
          value,
          integerValues,
          booleanValues,
          generateValues,
          relativePath :+ "value"
        ).map(-_)
      case IntExpr.Add(left, right) => binary(left, right)((a, b) => Right(a + b))
      case IntExpr.Subtract(left, right) => binary(left, right)((a, b) => Right(a - b))
      case IntExpr.Multiply(left, right) => binary(left, right)((a, b) => Right(a * b))
      case IntExpr.Divide(left, right) => binary(left, right) { (a, b) =>
        if (b == 0)
          Left(
            ExactSemanticFailure(
              IrDiagnosticCode.IntegerDivisorMayBeZero,
              "integer divisor is zero for an admitted exact assignment",
              relativePath :+ "right"
            )
          )
        else Right(a / b)
      }
      case IntExpr.Modulo(left, right) => binary(left, right) { (a, b) =>
        if (b == 0)
          Left(
            ExactSemanticFailure(
              IrDiagnosticCode.IntegerDivisorMayBeZero,
              "integer divisor is zero for an admitted exact assignment",
              relativePath :+ "right"
            )
          )
        else Right(a % b)
      }
      case IntExpr.Min(left, right) => binary(left, right)((a, b) => Right(a.min(b)))
      case IntExpr.Max(left, right) => binary(left, right)((a, b) => Right(a.max(b)))
      case IntExpr.Select(condition, yes, no) =>
        evaluateBool(
          condition,
          integerValues,
          booleanValues,
          generateValues,
          relativePath :+ "condition"
        ) match {
          case Left(problem) => Left(problem)
          case Right(true) =>
            evaluateInt(
              yes,
              integerValues,
              booleanValues,
              generateValues,
              relativePath :+ "when-true"
            )
          case Right(false) =>
            evaluateInt(
              no,
              integerValues,
              booleanValues,
              generateValues,
              relativePath :+ "when-false"
            )
        }
      case IntExpr.CeilLog2(value) =>
        evaluateInt(
          value,
          integerValues,
          booleanValues,
          generateValues,
          relativePath :+ "value"
        ).flatMap { resolved =>
          if (resolved < 1)
            Left(
              ExactSemanticFailure(
                IrDiagnosticCode.CeilLog2OperandInvalid,
                s"ceil-log2 operand is not positive for admitted value $resolved",
                relativePath
              )
            )
          else Right(ceilLog2(resolved))
        }
      case IntExpr.AddressWidth(value) =>
        evaluateInt(
          value,
          integerValues,
          booleanValues,
          generateValues,
          relativePath :+ "value"
        ).flatMap { resolved =>
          if (resolved < 1)
            Left(
              ExactSemanticFailure(
                IrDiagnosticCode.AddressWidthOperandInvalid,
                s"address-width operand is not positive for admitted value $resolved",
                relativePath
              )
            )
          else Right(BigInt(1).max(ceilLog2(resolved)))
        }
      case IntExpr.Pow2(exponent) =>
        evaluateInt(
          exponent,
          integerValues,
          booleanValues,
          generateValues,
          relativePath :+ "exponent"
        ).flatMap { resolved =>
          if (resolved < 0 || resolved > MaximumParameterDomainSize)
            Left(
              ExactSemanticFailure(
                IrDiagnosticCode.Pow2ExponentInvalid,
                s"power-of-two exponent must remain in [0, $MaximumParameterDomainSize], observed $resolved",
                relativePath
              )
            )
          else Right(BigInt(1) << resolved.toInt)
        }
    }
  }

  private def evaluateBool(
      expression: BoolExpr,
      integerValues: mutable.Map[ParameterId, BigInt],
      booleanValues: mutable.Map[ParameterId, Boolean],
      generateValues: mutable.Map[GenerateIndexId, BigInt],
      relativePath: Vector[String] = Vector.empty
  ): Either[ExactEvaluationFailure, Boolean] = {
    def integers(
        left: IntExpr,
        right: IntExpr
    )(operation: (BigInt, BigInt) => Boolean): Either[ExactEvaluationFailure, Boolean] =
      evaluateInt(
        left,
        integerValues,
        booleanValues,
        generateValues,
        relativePath :+ "left"
      ) match {
        case Left(problem) => Left(problem)
        case Right(leftValue) =>
          evaluateInt(
            right,
            integerValues,
            booleanValues,
            generateValues,
            relativePath :+ "right"
          ) match {
            case Left(problem)     => Left(problem)
            case Right(rightValue) => Right(operation(leftValue, rightValue))
          }
      }

    def booleans(
        left: BoolExpr,
        right: BoolExpr
    )(operation: (Boolean, Boolean) => Boolean): Either[ExactEvaluationFailure, Boolean] =
      evaluateBool(
        left,
        integerValues,
        booleanValues,
        generateValues,
        relativePath :+ "left"
      ) match {
        case Left(problem) => Left(problem)
        case Right(leftValue) =>
          evaluateBool(
            right,
            integerValues,
            booleanValues,
            generateValues,
            relativePath :+ "right"
          ) match {
            case Left(problem)     => Left(problem)
            case Right(rightValue) => Right(operation(leftValue, rightValue))
          }
      }

    expression match {
      case BoolExpr.Literal(value) => Right(value)
      case BoolExpr.ParameterRef(parameter) =>
        if (validParameterId(parameter))
          booleanValues.get(parameter).toRight(ExactEvaluationUnavailable)
        else Left(ExactEvaluationUnavailable)
      case BoolExpr.LessThan(left, right) => integers(left, right)(_ < _)
      case BoolExpr.LessThanOrEqual(left, right) => integers(left, right)(_ <= _)
      case BoolExpr.GreaterThan(left, right) => integers(left, right)(_ > _)
      case BoolExpr.GreaterThanOrEqual(left, right) => integers(left, right)(_ >= _)
      case BoolExpr.Equal(left, right) => integers(left, right)(_ == _)
      case BoolExpr.NotEqual(left, right) => integers(left, right)(_ != _)
      case BoolExpr.IsPow2(value) =>
        evaluateInt(
          value,
          integerValues,
          booleanValues,
          generateValues,
          relativePath :+ "value"
        ).map { resolved =>
          resolved > 0 && (resolved & (resolved - 1)) == 0
        }
      case BoolExpr.Not(value) =>
        evaluateBool(
          value,
          integerValues,
          booleanValues,
          generateValues,
          relativePath :+ "value"
        ).map(!_)
      case BoolExpr.And(left, right) => booleans(left, right)(_ && _)
      case BoolExpr.Or(left, right) => booleans(left, right)(_ || _)
    }
  }

  private def unresolvedParameter(
      id: String,
      path: Vector[String],
      location: Option[SourceLocation],
      diagnostics: DiagnosticCollector
  ): Unit = diagnostics.add(
    IrDiagnosticCode.ParameterUnresolved,
    s"parameter '$id' is not declared in this module",
    path,
    location
  )

  private def missingRtlOperator(
      path: Vector[String],
      location: Option[SourceLocation],
      diagnostics: DiagnosticCollector
  ): Unit = diagnostics.add(
    IrDiagnosticCode.RtlOperatorMissing,
    "runtime operator must be known",
    path :+ "operator",
    location
  )

  private def ceilLog2(value: BigInt): BigInt =
    if (value <= 1) BigInt(0) else BigInt((value - 1).bitLength)

  private def validateLocationContainer(
      location: Option[SourceLocation],
      path: Vector[String],
      diagnostics: DiagnosticCollector
  ): Unit = {
    if (location == null) {
      diagnostics.add(
        IrDiagnosticCode.LocationContainerMissing,
        "source-location option must not be null",
        path,
        None
      )
    } else location.foreach { value =>
      if (
        value == null || !nonEmpty(value.path) || value.line < 1 ||
        value.column < 1
      ) {
        diagnostics.add(
          IrDiagnosticCode.LocationInvalid,
          "source location requires a non-empty path and positive line/column",
          path,
          Option(value)
        )
      }
    }
  }

  private def requiredVector[A](
      value: Vector[A],
      label: String,
      path: Vector[String],
      diagnostics: DiagnosticCollector,
      location: Option[SourceLocation]
  ): Vector[A] = {
    if (value == null) {
      diagnostics.add(
        IrDiagnosticCode.CollectionMissing,
        s"$label collection must not be null",
        path,
        location
      )
      Vector.empty
    } else value
  }

  private def collectReferences(expression: RtlExpr): Vector[RtlExpr.Ref] = {
    if (expression == null) Vector.empty
    else expression match {
      case value: RtlExpr.Ref                 => Vector(value)
      case RtlExpr.Literal(_, _, _)           => Vector.empty
      case RtlExpr.Unary(_, value)            => collectReferences(value)
      case RtlExpr.Binary(_, left, right)     => collectReferences(left) ++ collectReferences(right)
      case RtlExpr.Mux(condition, yes, no)    => collectReferences(condition) ++ collectReferences(yes) ++ collectReferences(no)
      case RtlExpr.Concat(values)             => Option(values).getOrElse(Vector.empty).flatMap(collectReferences)
      case RtlExpr.BitSelect(value, index)    => collectReferences(value) ++ collectReferences(index)
      case RtlExpr.PartSelect(value, _, _)    => collectReferences(value)
      case RtlExpr.Resize(value, _, _)        => collectReferences(value)
      case RtlExpr.Cast(value, _)             => collectReferences(value)
    }
  }

  private def validateNullEntries[A >: Null](
      values: Vector[A],
      code: String,
      label: String,
      path: Vector[String],
      location: Option[SourceLocation],
      diagnostics: DiagnosticCollector
  ): Unit = values.zipWithIndex.foreach { case (value, index) =>
    if (value == null) {
      diagnostics.add(
        code,
        s"$label entry must not be null",
        path :+ index.toString,
        location
      )
    }
  }

  private def addDuplicates(
      values: Vector[(String, Option[SourceLocation])],
      code: String,
      label: String,
      path: Vector[String],
      diagnostics: DiagnosticCollector
  ): Unit = values
    .filter(value => nonEmpty(value._1))
    .groupBy(_._1)
    .toVector
    .sortBy(_._1)
    .foreach { case (id, occurrences) =>
      if (occurrences.size > 1) {
        diagnostics.add(
          code,
          s"$label '$id' is declared ${occurrences.size} times",
          path :+ id,
          occurrences.iterator.flatMap(_._2).toVector.headOption
        )
      }
    }

  private def validateIdentifier(
      value: String,
      missingCode: String,
      invalidCode: String,
      label: String,
      path: Vector[String],
      location: Option[SourceLocation],
      diagnostics: DiagnosticCollector
  ): Boolean = {
    if (value == null) {
      diagnostics.add(
        missingCode,
        s"$label must be present",
        path,
        location
      )
      false
    } else if (!validIdentifier(value)) {
      diagnostics.add(
        invalidCode,
        s"$label '$value' must be non-empty, normalized and contain no whitespace",
        path,
        location
      )
      false
    } else true
  }

  private def validIdentifier(value: String): Boolean =
    IrIdentifierSyntax.validate(value, "identifier") match {
      case Right(normalized) => normalized == value
      case Left(_)           => false
    }

  private def moduleIdValue(value: ModuleId): String =
    Option(value).map(_.value).orNull

  private def parameterIdValue(value: ParameterId): String =
    Option(value).map(_.value).orNull

  private def scopeIdValue(value: ScopeId): String =
    Option(value).map(_.value).orNull

  private def generateIndexIdValue(value: GenerateIndexId): String =
    Option(value).map(_.value).orNull

  private def symbolIdValue(value: SymbolId): String =
    Option(value).map(_.value).orNull

  private def driverIdValue(value: DriverId): String =
    Option(value).map(_.value).orNull

  private def referenceIdValue(value: ReferenceId): String =
    Option(value).map(_.value).orNull

  private def validModuleId(value: ModuleId): Boolean =
    validIdentifier(moduleIdValue(value))

  private def validParameterId(value: ParameterId): Boolean =
    validIdentifier(parameterIdValue(value))

  private def validScopeId(value: ScopeId): Boolean =
    validIdentifier(scopeIdValue(value))

  private def validGenerateIndexId(value: GenerateIndexId): Boolean =
    validIdentifier(generateIndexIdValue(value))

  private def validSymbolId(value: SymbolId): Boolean =
    validIdentifier(symbolIdValue(value))

  private def scopeIsAncestor(
      scopeById: Map[ScopeId, Scope],
      ancestor: ScopeId,
      descendant: ScopeId
  ): Boolean = {
    if (!validScopeId(ancestor) || !validScopeId(descendant)) return false
    var current: Option[ScopeId] = Some(descendant)
    var visited = Set.empty[ScopeId]
    var found = false
    while (current.nonEmpty && !found) {
      val id = current.get
      if (id == ancestor) found = true
      else if (visited.contains(id)) current = None
      else {
        visited += id
        current = scopeById
          .get(id)
          .flatMap(scope => Option(scope.parent).flatten.filter(validScopeId))
      }
    }
    found
  }

  private def safeLocation(
      value: Option[SourceLocation]
  ): Option[SourceLocation] = Option(value).flatten.filter(_ != null)

  private def idText(value: Any): String = value match {
    case null                   => "<null>"
    case id: ModuleId           => Option(id.value).getOrElse("<null>")
    case id: ScopeId            => Option(id.value).getOrElse("<null>")
    case id: SymbolId           => Option(id.value).getOrElse("<null>")
    case id: DriverId           => Option(id.value).getOrElse("<null>")
    case id: ReferenceId        => Option(id.value).getOrElse("<null>")
    case id: ParameterId        => Option(id.value).getOrElse("<null>")
    case id: GenerateIndexId    => Option(id.value).getOrElse("<null>")
    case other                  => other.toString
  }

  private def nonEmpty(value: String): Boolean =
    value != null && value.trim.nonEmpty
}

package spinal.core

/** Declare a child-local scalar formal without converting its actual to a
  * Scala witness. Packed-width validity remains a separate consumer check.
  */
object ElabFormalParameter {
  private object Declarations
  private final class State(val owner: Component) {
    val names = scala.collection.mutable.HashSet.empty[String]
    val entries = scala.collection.mutable.ArrayBuffer.empty[(ElaborationIntegerParameter,
      ElaborationIntegerExpression,Option[String])]
    var sealedInventory = false
    owner.addPrePopTask(() => {
      require(!sealedInventory,"formal inventory was already sealed")
      sealedInventory=true
      ExternalFormalParameterRegistry.retainDeclaredTypedParameters(owner,entries.toVector)
      entries.foreach { case (formal,actual,_) =>
        ParameterizedVec.retainComponentFormal(owner,formal,actual)
        NativeFormalWidthProjection.retain(owner,formal,actual)
      }
    })
  }
  def apply(actual: ElabInt, name: String, minimum: BigInt = 1,
      maximum: BigInt = 4096): ElabInt =
    declare(actual, name, minimum, maximum, None)

  private[core] def declare(actual: ElabInt, name: String, minimum: BigInt,
      maximum: BigInt, representation: Option[ElaborationPackedParameter]): ElabInt = {
    val owner = Option(Component.current).getOrElse {
      ParameterizedVerilogException.fail("SPINAL-ELAB-FORMAL-PARENT-MISSING",
        "a formal must be declared inside its owning Component", None)
    }
    if (actual == null || name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*"))
      ParameterizedVerilogException.fail("SPINAL-ELAB-FORMAL-DECLARATION-INVALID",
        "a formal needs a non-null typed actual and a portable name", None)
    val expression = ElaborationPublicationValue.projected(actual, "native formal actual",
      "SPINAL-ELAB-FORMAL-ACTUAL-EXACT-DOMAIN-REQUIRED", requireProjectedExactExtrema = false)
    if ((minimum < 0 && !representation.exists(_.signed)) || maximum < minimum || maximum > Int.MaxValue ||
        expression.minimum < minimum || expression.maximum > maximum ||
        expression.default < minimum || expression.default > maximum || expression.generateIndex.nonEmpty)
      ParameterizedVerilogException.fail("SPINAL-ELAB-FORMAL-DOMAIN-INVALID",
        s"formal '$name' in [$minimum,$maximum] cannot admit '${expression.verilog}' in [${expression.minimum},${expression.maximum}]",
        expression.sourceLocation)
    val state = owner.userCache.getOrElseUpdate(Declarations, new State(owner)).asInstanceOf[State]
    require(state.owner eq owner, "formal declaration storage belongs to another Component")
    require(!state.sealedInventory,"formal declaration was added after its complete inventory was sealed")
    if (!state.names.add(name))
      ParameterizedVerilogException.fail("SPINAL-ELAB-FORMAL-NAME-DUPLICATE",
        s"formal '$name' is already declared on this Component", expression.sourceLocation)
    require(representation.forall(p => minimum >= p.minimum && maximum <= p.maximum),
      "SPINAL-PARAM-REPRESENTATION-OVERFLOW: domain must fit its declared packed type")
    val formal = ElaborationIntegerParameter(name, expression.default, minimum, maximum, representation)
    val definition = ElabInt.directParameter(formal, expression.sourceLocation)
    // Diagnostic provenance only: a default/literal often has no source span.
    // Retain the declaring Component's constructor frame while it is live,
    // independently of optional native trace retries and emitted comments.
    val declarationLocation = expression.sourceLocation.orElse {
      new Throwable().getStackTrace.find(frame =>
        frame.getClassName == owner.getClass.getName && frame.getFileName != null && frame.getLineNumber > 0)
        .map(frame => s"${frame.getFileName}:${frame.getLineNumber}")
    }
    state.entries += ((formal,expression,declarationLocation))
    definition
  }
}

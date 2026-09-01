package spinal.core

import java.lang.ref.{ReferenceQueue, WeakReference}

import scala.collection.mutable

/**
  * Definition-side symbolic address retained for one exact native Scala
  * `BigInt` object passed into the untouched SpinalHDL bus-slave factory.
  */
final case class ExternalNativeAxi4SlaveFactoryAddressRecord(
    expression: ElaborationIntegerExpression,
    witness: BigInt,
    stableName: String,
    sourceLocation: Option[String]
)

private[core] final class ExternalNativeAxi4AddressIdentityRef(
    value: BigInt,
    queue: ReferenceQueue[BigInt]
) extends WeakReference[BigInt](value, queue) {
  private val identityHash = System.identityHashCode(value)

  override def hashCode(): Int = identityHash

  override def equals(other: Any): Boolean = other match {
    case that: ExternalNativeAxi4AddressIdentityRef =>
      (this eq that) || {
        val left = get()
        val right = that.get()
        (left ne null) && (right ne null) && (left eq right)
      }
    case _ => false
  }
}

/**
  * MorphHDL-only parameterization support for the real native
  * `spinal.lib.bus.amba4.axi.Axi4SlaveFactory`.
  *
  * The native factory remains the only implementation of register mapping,
  * address grouping, read/write actions, AXI handshakes and decode generation.
  * This object merely carries exact source provenance from one ordinary Scala
  * `Int` address expression into the exact `BigInt` stored by native
  * `SingleMapping`, then presents that expression as a parameterized UInt case
  * key when the native delayed `Axi4SlaveFactory.build()` executes.
  *
  * No lookup is ever performed by numeric witness, signal name, module name or
  * emitted Verilog text.
  */
object ExternalNativeAxi4SlaveFactoryParameterization {
  private val queue = new ReferenceQueue[BigInt]()
  private val retained = mutable.HashMap.empty[
    ExternalNativeAxi4AddressIdentityRef,
    ExternalNativeAxi4SlaveFactoryAddressRecord
  ]
  private val materializationCounts = mutable.HashMap.empty[
    ExternalNativeAxi4AddressIdentityRef,
    Int
  ]

  private def rendered(file: String, line: Int): String = s"$file:$line"

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String]
  ): Nothing =
    throw new ParameterizedVerilogException(code, detail, sourceLocation)

  private def reap(): Unit = {
    var reference = queue.poll().asInstanceOf[ExternalNativeAxi4AddressIdentityRef]
    while (reference != null) {
      retained.remove(reference)
      materializationCounts.remove(reference)
      reference = queue.poll().asInstanceOf[ExternalNativeAxi4AddressIdentityRef]
    }
  }

  private def attach(
      value: BigInt,
      expression: ElaborationIntegerExpression,
      stableName: String,
      sourceLocation: Option[String]
  ): BigInt = synchronized {
    if (value == null)
      throw new IllegalArgumentException("native AXI4 factory address must not be null")
    if (expression == null)
      throw new IllegalArgumentException("native AXI4 factory address expression must not be null")
    if (expression.parameters.isEmpty) {
      fail(
        "MORPH-FRONTEND-NATIVE-AXI4-ADDRESS-PARAMETER-MISSING",
        s"retained AXI4 factory address '${expression.verilog}' has no formal parameter",
        sourceLocation.orElse(expression.sourceLocation)
      )
    }
    if (expression.default != value) {
      fail(
        "MORPH-FRONTEND-NATIVE-AXI4-ADDRESS-WITNESS-MISMATCH",
        s"retained AXI4 factory address '${expression.verilog}' defaults to ${expression.default}, but native witness is $value",
        sourceLocation.orElse(expression.sourceLocation)
      )
    }
    if (expression.minimum < 0 || expression.maximum < expression.minimum) {
      fail(
        "MORPH-FRONTEND-NATIVE-AXI4-ADDRESS-DOMAIN-UNSUPPORTED",
        s"retained AXI4 factory address '${expression.verilog}' has invalid unsigned domain [${expression.minimum}, ${expression.maximum}]",
        sourceLocation.orElse(expression.sourceLocation)
      )
    }
    if (stableName == null || stableName.trim.isEmpty)
      throw new IllegalArgumentException("native AXI4 factory address stable name must not be empty")

    reap()
    val key = new ExternalNativeAxi4AddressIdentityRef(value, null)
    val incoming = ExternalNativeAxi4SlaveFactoryAddressRecord(
      expression = expression,
      witness = value,
      stableName = stableName,
      sourceLocation = sourceLocation.orElse(expression.sourceLocation)
    )
    retained.get(key) match {
      case Some(existing) if existing != incoming =>
        fail(
          "MORPH-FRONTEND-NATIVE-AXI4-ADDRESS-IDENTITY-CONFLICT",
          "one exact native BigInt address object received conflicting symbolic provenance",
          incoming.sourceLocation
        )
      case Some(_) =>
      case None =>
        val retainedKey = new ExternalNativeAxi4AddressIdentityRef(value, queue)
        retained.update(retainedKey, incoming)
        materializationCounts.update(retainedKey, 0)
    }
    value
  }

  /**
    * Compiler-inserted call-site bridge. Outside a live formalization boundary
    * this is exactly an ordinary `BigInt(value)` conversion. The public
    * constructor is required here: Scala 2.13's companion `apply(BigInteger)`
    * routes small values through its shared cache, which would turn equal
    * numeric witnesses into false exact-object identity.
    */
  def compilerAddress(
      value: Int,
      reference: String,
      stableName: String,
      file: String,
      line: Int
  ): BigInt = {
    val address = new BigInt(new java.math.BigInteger(value.toString))
    ExternalNativeIntShadowRegistry
      .definitionExpressionTracked(
        reference = reference,
        witness = value,
        sourceLocation = rendered(file, line),
        positiveWidth = false
      )
      .foreach { expression =>
        attach(
          address,
          expression,
          stableName,
          Some(rendered(file, line))
        )
      }
    address
  }

  /** Exact-object lookup used only by compiler-instrumented native factory code. */
  def recordOf(
      value: BigInt
  ): Option[ExternalNativeAxi4SlaveFactoryAddressRecord] = synchronized {
    if (value == null) None
    else {
      reap()
      retained.get(new ExternalNativeAxi4AddressIdentityRef(value, null))
    }
  }

  private def nextMaterialization(
      value: BigInt
  ): Option[(ExternalNativeAxi4SlaveFactoryAddressRecord, Int)] = synchronized {
    if (value == null) None
    else {
      reap()
      val key = new ExternalNativeAxi4AddressIdentityRef(value, null)
      retained.get(key).map { record =>
        val occurrence = materializationCounts.getOrElse(key, 0)
        materializationCounts.update(key, occurrence + 1)
        record -> occurrence
      }
    }
  }

  /**
    * Compiler-inserted bridge inside the native `is(address.address)` call.
    * Untagged addresses are returned unchanged. Tagged addresses become an
    * exact UInt case key carrying the canonical formal expression while the
    * surrounding switch, cases and bodies remain entirely native SpinalHDL.
    */
  def compilerCaseKey(
      value: BigInt,
      file: String,
      line: Int
  ): Any = nextMaterialization(value) match {
    case None => value
    case Some((record, occurrence)) =>
      val source = Some(rendered(file, line)).orElse(record.sourceLocation)
      val switchValue = Option(SwitchStack.get)
        .map(_.statement.value)
        .getOrElse {
          fail(
            "MORPH-FRONTEND-NATIVE-AXI4-SWITCH-CONTEXT-MISSING",
            "parameterized native AXI4 address was consumed outside the factory switch context",
            source
          )
        }
      switchValue match {
        case prototype: UInt =>
          val carrierWidth = prototype.getBitsWidth
          if (
            carrierWidth < 1 ||
            record.expression.maximum >= (BigInt(1) << carrierWidth)
          ) {
            fail(
              "MORPH-FRONTEND-NATIVE-AXI4-ADDRESS-WIDTH-INSUFFICIENT",
              s"AXI4 address expression '${record.expression.verilog}' reaches ${record.expression.maximum}, outside the native $carrierWidth-bit address width",
              source.orElse(record.sourceLocation)
            )
          }
          val result = UInt(carrierWidth bits)
          ParameterizedWidth.copyShape(prototype, result)
          result.setName(s"${record.stableName}_case_$occurrence")
          result.assignFrom(
            spinal.core.internals.UIntLiteral(
              record.witness,
              null,
              carrierWidth
            )
          )
          ExternalParameterizedValueRegistry.attach(
            value = result,
            expression = record.expression,
            witness = record.witness,
            sourceLocation = source.orElse(record.sourceLocation)
          )
        case other =>
          fail(
            "MORPH-FRONTEND-NATIVE-AXI4-SWITCH-TYPE-UNSUPPORTED",
            s"native AXI4 factory address switch has unsupported type '${other.getClass.getName}' instead of UInt",
            source.orElse(record.sourceLocation)
          )
      }
  }

  /** Test-only observability without exposing mutable registry state. */
  def liveAddressCount: Int = synchronized {
    reap()
    retained.size
  }
}

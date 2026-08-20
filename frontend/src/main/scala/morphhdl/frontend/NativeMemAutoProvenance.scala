package morphhdl.frontend

import java.lang.ref.{ReferenceQueue, WeakReference}

import scala.collection.mutable

import spinal.core.{
  Data,
  ExternalParameterizedMemoryRegistry,
  HardType,
  Mem,
  ParameterizedMemoryDepth
}

/** Stable structural signature retained beside one native Mem call site. */
private[morphhdl] final case class NativeMemDepthSignature(
    verilog: String,
    default: BigInt,
    minimum: BigInt,
    maximum: BigInt,
    parameters: Vector[(String, BigInt, BigInt, BigInt)],
    generateIndex: Option[String]
) {
  def rendered: String = {
    val schemas = parameters
      .map { case (name, defaultValue, minimumValue, maximumValue) =>
        s"$name=$defaultValue[$minimumValue,$maximumValue]"
      }
      .mkString(",")
    s"$verilog|$default|$minimum|$maximum|$schemas|${generateIndex.getOrElse("")}"
  }
}

private[morphhdl] object NativeMemDepthSignature {
  def from(depth: ParameterizedMemoryDepth): NativeMemDepthSignature = {
    val expression = depth.expression
    NativeMemDepthSignature(
      verilog = expression.verilog,
      default = expression.default,
      minimum = expression.minimum,
      maximum = expression.maximum,
      parameters = expression.parameters.map { parameter =>
        (
          parameter.name,
          parameter.default,
          parameter.minimum,
          parameter.maximum
        )
      },
      generateIndex = expression.generateIndex
    )
  }
}

/**
  * Deterministic token for the native Mem use site and the exact symbolic value
  * that reached it. The concrete witness is deliberately not part of the token:
  * equal integers alone never establish symbolic provenance.
  */
private[morphhdl] final case class NativeMemCallSiteToken(
    callSite: SourceOrigin,
    valueOrigin: SourceOrigin,
    signature: NativeMemDepthSignature
) {
  def rendered: String =
    s"${callSite.rendered}|${valueOrigin.rendered}|${signature.rendered}"
}

private[morphhdl] final case class NativeMemProvenanceRecord(
    token: NativeMemCallSiteToken,
    depth: ParameterizedMemoryDepth
)

/** Weak key with native Mem object-identity semantics. */
private[morphhdl] final class NativeMemIdentityReference(
    value: Mem[_],
    queue: ReferenceQueue[Mem[_]]
) extends WeakReference[Mem[_]](value, queue) {
  private val identityHash = System.identityHashCode(value)

  override def hashCode(): Int = identityHash

  override def equals(other: Any): Boolean = other match {
    case that: NativeMemIdentityReference =>
      (this eq that) || {
        val left = get()
        val right = that.get()
        (left ne null) && (right ne null) && (left eq right)
      }
    case _ => false
  }
}

/**
  * Automatic HdlInt-to-native-Mem handoff.
  *
  * Only the checked concrete witness crosses into untouched SpinalHDL. The
  * complete symbolic depth remains external and is associated immediately with
  * the exact returned native Mem object. No concrete-value lookup is performed.
  */
private[morphhdl] object NativeMemAutoProvenance {
  private val queue = new ReferenceQueue[Mem[_]]()
  private val retained = mutable.HashMap.empty[
    NativeMemIdentityReference,
    NativeMemProvenanceRecord
  ]

  private def reap(): Unit = {
    var reference = queue.poll().asInstanceOf[NativeMemIdentityReference]
    while (reference != null) {
      retained.remove(reference)
      reference = queue.poll().asInstanceOf[NativeMemIdentityReference]
    }
  }

  def create[T <: Data](
      factory: spinal.core.Mem.type,
      wordType: HardType[T],
      wordCount: HdlInt
  )(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): Mem[T] = {
    val callSite = SourceOrigin.capture
    if (wordType eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-NATIVE-MEM-WORD-TYPE-NULL",
        "native Mem construction requires a non-null HardType",
        callSite
      )
    }
    if (wordCount eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-NATIVE-MEM-DEPTH-NULL",
        "native Mem construction requires a non-null HdlInt depth",
        callSite
      )
    }

    val depth = wordCount.toParameterizedMemoryDepth(file, line)
    val token = NativeMemCallSiteToken(
      callSite = callSite,
      valueOrigin = wordCount.origin,
      signature = NativeMemDepthSignature.from(depth)
    )
    val memory = factory(wordType, depth.value)
    attach(memory, depth, token)
  }

  private[morphhdl] def attach[T <: Data](
      memory: Mem[T],
      depth: ParameterizedMemoryDepth,
      token: NativeMemCallSiteToken
  ): Mem[T] = synchronized {
    if (token eq null) {
      throw new IllegalArgumentException("native Mem provenance token must not be null")
    }
    if (memory eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-NATIVE-MEM-OBJECT-NULL",
        "native Mem provenance requires a non-null native memory object",
        token.callSite
      )
    }
    if (depth eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-NATIVE-MEM-DEPTH-NULL",
        "native Mem provenance requires a non-null retained depth",
        token.callSite
      )
    }

    reap()
    val lookup = new NativeMemIdentityReference(memory, null)
    val incoming = NativeMemProvenanceRecord(token, depth)
    retained.get(lookup) match {
      case Some(existing) if existing == incoming => memory
      case Some(existing) =>
        FrontendException.failAt(
          "MORPH-FRONTEND-NATIVE-MEM-PROVENANCE-CONFLICT",
          s"one native Mem object received conflicting symbolic-depth provenance '${existing.token.rendered}' and '${token.rendered}'",
          token.callSite
        )
      case None =>
        ExternalParameterizedMemoryRegistry.attach(memory, depth)
        retained.update(new NativeMemIdentityReference(memory, queue), incoming)
        memory
    }
  }

  private[morphhdl] def recordOf(
      memory: Mem[_]
  ): Option[NativeMemProvenanceRecord] = synchronized {
    if (memory eq null) None
    else {
      reap()
      retained.get(new NativeMemIdentityReference(memory, null))
    }
  }
}

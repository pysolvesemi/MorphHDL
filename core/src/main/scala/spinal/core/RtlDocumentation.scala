package spinal.core

import scala.annotation.StaticAnnotation
import scala.collection.mutable
import spinal.core.internals._

/** Documentation is optional metadata, never a hardware retention directive. */
final class doc(val text: String) extends StaticAnnotation

final case class RtlDocumentationOptions(captureScalaComments: Boolean = false, captureScaladoc: Boolean = false)

object RtlDocumentationOptions {
  def apply(config: SpinalConfig, captureScalaComments: Boolean, captureScaladoc: Boolean): SpinalConfig = {
    val flags = config.flags.clone()
    flags.retain(!_.isInstanceOf[RtlDocumentationOptions])
    flags += RtlDocumentationOptions(captureScalaComments, captureScaladoc)
    config.copy(flags = flags, scopeProperties = config.scopeProperties.clone(), phasesInserters = config.phasesInserters.clone())
  }
  def of(config: SpinalConfig): RtlDocumentationOptions =
    config.flags.collectFirst { case v: RtlDocumentationOptions => v }.getOrElse(RtlDocumentationOptions())
}

final class RtlDocTag(text: String, val origin: String, val location: String,
    val projection: Boolean = false, val aggregate: Data = null) extends CommentTag(text) {
  override def canSymplifyHost: Boolean = true
}

/** Identity shared by the statements created in a documented region. */
trait RtlDocumentationAnchor {
  private[spinal] var rtlDocumentation: Vector[RtlDocumentation.RegionNote] = RtlDocumentation.activeRegions
}

object rtlDoc {
  def apply[T](message: String)(body: => T): T = RtlDocumentation.region(message)(body)
}

object RtlDocumentation {
  final class RegionNote(val content: String) {
    private[spinal] val component = Component.current
    private[spinal] var valid = false
    private[spinal] var generatedOwners = Vector.empty[AnyRef]
  }
  private val regions = new ThreadLocal[Vector[RegionNote]] {
    override def initialValue(): Vector[RegionNote] = Vector.empty
  }
  private[spinal] def activeRegions: Vector[RegionNote] = regions.get()
  def automaticRegion[T](message: String, scaladoc: Boolean)(body: => T): T = {
    val options = RtlDocumentationOptions.of(GlobalData.get.config)
    if (if (scaladoc) options.captureScaladoc else options.captureScalaComments) region(message)(body)
    else body
  }
  def region[T](message: String)(body: => T): T = {
    val content = text(message)
    if (content.isEmpty) return body
    val previous = regions.get()
    val note = new RegionNote(content)
    regions.set(previous :+ note)
    try {
      val result = body
      note.valid = true
      result
    } finally regions.set(previous)
  }

  private[spinal] def claimGenerated(host: RtlDocumentationAnchor,
      children: scala.collection.Seq[RtlDocumentationAnchor] = Vector.empty): Unit =
    host.rtlDocumentation.foreach { note =>
      val siblings = note.generatedOwners.filterNot(owner => (owner eq host) || children.exists(_ eq owner))
      note.generatedOwners = siblings :+ host
    }

  private[spinal] def captureGeneratedBody(host: RtlDocumentationAnchor, members: scala.collection.Seq[RtlDocumentationAnchor]): Unit = {
    val shared = members.headOption.toVector.flatMap(_.rtlDocumentation).filter(n => members.forall(_.rtlDocumentation.contains(n)))
    host.rtlDocumentation = (host.rtlDocumentation ++ shared).distinct
    claimGenerated(host, members)
  }
  private[spinal] def generatedLines(host: RtlDocumentationAnchor, indent: String): String =
    lines(host.rtlDocumentation.filter(n => n.valid && n.generatedOwners.exists(_ eq host)).map(_.content), indent)

  /** Exact emission envelopes transport free-standing comments with their owner
    * through relocation. They are internal handoff markers, removed at publication. */
  private[spinal] final class Emission(val host: AnyRef, val ordinal: Int) extends SpinalTag {
    override def canSymplifyHost: Boolean = true
    def begin: String = s"/* MORPHHDL_RTL_DOC_BEGIN_$ordinal */"
    def end: String = s"/* MORPHHDL_RTL_DOC_END_$ordinal */"
  }
  private[spinal] def emissions(component: Component): Vector[Emission] =
    component.getTags().toVector.collect { case e: Emission => e }
  private[spinal] def envelope(component: Component, host: AnyRef, content: String): String = {
    if (content.isEmpty || !GlobalData.get.config.flags.contains(Deferred)) return content
    val record = new Emission(host, emissions(component).size)
    component.addTag(record)
    record.begin + "\n" + content + record.end + "\n"
  }
  private[spinal] def emissionRanges(component: Component, source: Vector[String]): Vector[(AnyRef, Int, Int)] =
    emissions(component).map { record =>
      val starts = source.indices.filter(i => source(i).trim == record.begin)
      val ends = source.indices.filter(i => source(i).trim == record.end)
      require(starts.size == 1 && ends.size == 1 && starts.head < ends.head,
        "RTL documentation emission envelope was lost or duplicated")
      (record.host, starts.head, ends.head)
    }
  private[spinal] def finishPublication(component: Component, source: String): String = {
    val markers = emissions(component).flatMap(e => Vector(e.begin, e.end)).toSet
    source.split("\n", -1).filterNot(line => markers(line.trim)).mkString("\n")
  }

  /** Morph publication defers declaration documentation until layout lowering. */
  object Deferred
  def text(value: String): String = {
    require(value != null, "RTL documentation must not be null")
    require(!value.exists(c => c == '\u0000' || (c < ' ' && c != '\n' && c != '\r' && c != '\t')),
      "RTL documentation contains an unsupported control character")
    value.replace("\r\n", "\n").replace('\r', '\n')
  }
  def attach[T](value: T, message: String, origin: String = "explicit", location: String = "", definition: Boolean = false): T = {
    val content = text(message)
    val automatic = origin == "scala" || origin == "scaladoc"
    val options = RtlDocumentationOptions.of(GlobalData.get.config)
    if (origin == "unavailable") {
      require(!options.captureScalaComments && !options.captureScaladoc, content)
      return value
    }
    val enabled = !automatic || (if (origin == "scaladoc") options.captureScaladoc else options.captureScalaComments)
    if (enabled && content.nonEmpty) {
      val tag = new RtlDocTag(content, origin, location)
      value match {
        case c: Component if definition => c.definition.addTag(tag)
        case data: MultiData =>
          data.spinalTags += tag
          val projected = new RtlDocTag(content, origin, location, projection = true, aggregate = data)
          data.flatten.foreach(_.addTag(projected))
        case h: SpinalTagReady => h.addTag(tag)
        case _ if automatic => // Ordinary Scala declarations do not produce RTL.
        case _ => throw new IllegalArgumentException("RTL documentation requires a hardware declaration")
      }
    }
    value
  }
  def comments(host: SpinalTagReady, projections: Boolean = true): Vector[String] =
    host.getTags().toVector.collect {
      case t: RtlDocTag if projections || !t.projection => t.comment
      case t: CommentTag if !t.isInstanceOf[RtlDocTag] => text(t.comment)
    }.filter(_.nonEmpty)
  def lines(comments: Seq[String], indent: String = ""): String =
    comments.flatMap(text(_).split("\n", -1)).map(indent + "// " + _ + "\n").mkString

  def declarationBindings(component: Component, excluded: Set[BaseType] = Set.empty,
      seen: mutable.Set[RtlDocTag] = mutable.HashSet.empty[RtlDocTag]): Map[String, Vector[String]] = {
    val result = mutable.LinkedHashMap.empty[String, Vector[String]]
    component.dslBody.walkStatements {
      case value: BaseType if value.component == component && !value.isSuffix && !excluded(value) =>
        val notes = value.getTags().toVector.collect {
          case t: RtlDocTag if !t.projection || seen.add(t) => t.comment
          case t: CommentTag if !t.isInstanceOf[RtlDocTag] => text(t.comment)
        }.filter(_.nonEmpty)
        if (notes.nonEmpty && value.getName() != null) result(value.getName()) = notes
      case _ =>
    }
    result.toMap
  }

  /** Names are obtained from live declarations/retained layout plans, not guessed
    * from source spelling. Missing declarations correspond to removed hardware. */
  def publish(source: String, bindings: Map[String, Vector[String]]): String = {
    if (bindings.isEmpty) return source
    val declaration = "^([ \\t]*)(?:\\(\\*.*?\\*\\)\\s*)*(?:(?:input|output|inout)\\s+)?(?:wire|reg)\\b.*".r
    source.split("\n", -1).map { line =>
      line match {
        case declaration(indent) =>
          val matches = bindings.toVector.filter { case (name, _) =>
            ("\\b" + java.util.regex.Pattern.quote(name) + "\\s*(?:\\[[^;]*\\]\\s*)?[,;]?\\s*$").r.findFirstIn(line.replaceAll("/\\*.*?\\*/", "")).nonEmpty
          }
          require(matches.size <= 1, "ambiguous RTL documentation declaration")
          matches.headOption.map { case (_, notes) =>
            if (notes.size == 1 && !notes.head.contains("\n") && notes.head.length <= 80) line + " // " + notes.head
            else lines(notes, indent) + line
          }.getOrElse(line)
        case _ => line
      }
    }.mkString("\n")
  }
  def signature(component: Component): String = {
    def encode(values: Seq[String]): String = values.map(value => value.length.toString + ":" + value).mkString
    val noteIds = mutable.LinkedHashMap.empty[RegionNote, Int]
    val ownerIds = new java.util.IdentityHashMap[AnyRef, java.lang.Integer]()
    def ownerSignature(owner: AnyRef): String = {
      if (!ownerIds.containsKey(owner)) ownerIds.put(owner, ownerIds.size())
      encode(Vector(owner.getClass.getName, ownerIds.get(owner).toString))
    }
    val regions = mutable.ArrayBuffer.empty[String]
    component.dslBody.walkStatements { statement =>
      val notes = statement.rtlDocumentation.filter(n => n.valid && (n.component eq component)).map { note =>
        val id = noteIds.getOrElseUpdate(note, noteIds.size)
        encode(Vector(id.toString, note.content, encode(note.generatedOwners.map(ownerSignature))))
      }
      if (notes.nonEmpty) regions += encode(notes)
    }
    val declarations = declarationBindings(component).toVector.sortBy(_._1).map {
      case (name, notes) => encode(name +: notes)
    }
    encode(Vector(encode(comments(component.definition)), encode(declarations), encode(regions.toVector)))
  }
}

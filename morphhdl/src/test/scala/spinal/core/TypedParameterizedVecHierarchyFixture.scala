package spinal.core

/** Typed child-formal fixture for the end-to-end parameterized Vec tests.
  *
  * Vec element width is visible on each ordinary leaf port and therefore uses
  * the existing exact geometry binding.  Vec depth is aggregate structural
  * metadata, so it crosses the hierarchy through the typed scalar-formal
  * boundary.  No native-Int shadow participates.
  */
object TypedParameterizedVecHierarchyFixture {
  final class VecChild(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("TypedVecChild")
    val vecIn = in(Vec(UInt(width bits), depth)).setName("child_vec_in")
    val vecOut = out(Vec(UInt(width bits), depth)).setName("child_vec_out")
    vecOut := vecIn
  }

  final class VecParent(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("TypedVecParent")
    val vecIn = in(Vec(UInt(width bits), depth)).setName("parent_vec_in")
    val vecOut = out(Vec(UInt(width bits), depth)).setName("parent_vec_out")
    val child = ElabFormalComponent
      .parameter(
        actual = depth,
        name = "DEPTH",
        minimum = BigInt(1),
        maximum = BigInt(8)
      )(childDepth => new VecChild(width, childDepth))
      .setName("child")
    child.vecIn := vecIn
    vecOut := child.vecOut
  }

  /** Parent and child deliberately use different textual depth expressions.
    * The retained child formal is the only authority relating `DEPTH + 1` at
    * the parent boundary to `DEPTH` inside the child module.
    */
  final class CompoundVecParent(width: ElabInt, baseDepth: ElabInt) extends Component {
    setDefinitionName("TypedCompoundVecParent")
    private val actualDepth = baseDepth + 1
    val vecIn = in(Vec(UInt(width bits), actualDepth)).setName("parent_vec_in")
    val vecOut = out(Vec(UInt(width bits), actualDepth)).setName("parent_vec_out")
    val child = ElabFormalComponent
      .parameter(
        actual = actualDepth,
        name = "DEPTH",
        minimum = BigInt(2),
        maximum = BigInt(8)
      )(childDepth => new VecChild(width, childDepth))
      .setName("child")
    child.vecIn := vecIn
    vecOut := child.vecOut
  }

  final class VecInputOnlyChild(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("TypedVecInputOnlyChild")
    val vecIn = in(Vec(UInt(width bits), depth)).setName("child_vec_in")
    val observed = out(UInt(width bits)).setName("child_observed")
    observed := vecIn(0)
  }

  /** Adversarial native wiring that deliberately bypasses the authoritative
    * Vec assignment/autoconnect operation. The hierarchy publisher must not
    * infer aggregate intent from the emitted carrier leaf connections.
    */
  final class LeafwiseVecParent(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("TypedLeafwiseVecParent")
    val vecIn = in(Vec(UInt(width bits), depth)).setName("parent_vec_in")
    val observed = out(UInt(width bits)).setName("parent_observed")
    val child = ElabFormalComponent
      .parameter(
        actual = depth,
        name = "DEPTH",
        minimum = BigInt(1),
        maximum = BigInt(8)
      )(childDepth => new VecInputOnlyChild(width, childDepth))
      .setName("child")

    child.vecIn.vec.zip(vecIn.vec).foreach { case (target, source) =>
      target := source
    }
    observed := child.observed
  }
}

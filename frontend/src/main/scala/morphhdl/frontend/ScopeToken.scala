package morphhdl.frontend

private[frontend] final class ScopeToken(val indexName: String) {
  private var open = true

  def isOpen: Boolean = synchronized(open)

  def close(): Unit = synchronized {
    open = false
  }
}

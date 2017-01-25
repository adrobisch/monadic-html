package mhtml

import org.scalajs.dom
import org.scalajs.dom.raw.{Node => DomNode}
import scala.scalajs.js
import scala.xml.{Node => XmlNode, _}

/** Side-effectly mounts an `xml.Node` to a `org.scalajs.dom.raw.Node`. */
object mount {
  def apply(parent: DomNode, child: XmlNode, config: MountSettings): Unit = { mountNode(parent, child, None, config); () }
  def apply(parent: DomNode, obs: Rx[XmlNode], config: MountSettings): Unit = { mountNode(parent, new Atom(obs), None, config); () }
  def apply(parent: DomNode, child: XmlNode): Unit = { mountNode(parent, child, None, MountSettings.default); () }
  def apply(parent: DomNode, obs: Rx[XmlNode]): Unit = { mountNode(parent, new Atom(obs), None, MountSettings.default); () }

  private def mountNode(parent: DomNode, child: XmlNode, startPoint: Option[DomNode], config: MountSettings): Cancelable =
    child match {
      case e @ Elem(_, label, metadata, scope, child @ _*) =>
        config.inspectElement(label)
        println(s"$label:$scope${scope.get.url}")
        val elemNode = e.namespace.map(dom.document.createElementNS(_, label)).getOrElse(dom.document.createElement(label))
        val cancelMetadata = metadata.map { m => mountMetadata(elemNode, m, m.value.asInstanceOf[Atom[_]].data, config) }
        val cancelChild = child.map(c => mountNode(elemNode, c, None, config))
        parent.mountHere(elemNode, startPoint)
        Cancelable { () => cancelMetadata.foreach(_.cancel()); cancelChild.foreach(_.cancel()) }

      case e: EntityRef  =>
        val er = config.transformEntityRef(e.entityName)
        parent.mountHere(dom.document.createTextNode(er), startPoint)
        Cancelable.empty

      case Comment(text) =>
        parent.mountHere(dom.document.createComment(text), startPoint)
        Cancelable.empty

      case Group(nodes)  =>
        val cancels = nodes.map(n => mountNode(parent, n, startPoint, config))
        Cancelable(() => cancels.foreach(_.cancel))

      case a: Atom[_] => a.data match {
        case rx: Rx[_] =>
          val (start, end) = parent.createMountSection()
          var cancelable = Cancelable.empty
          rx.foreach { v =>
            parent.cleanMountSection(start, end)
            cancelable.cancel
            cancelable = v match {
              case n: XmlNode  =>
                mountNode(parent, n, Some(start), config)
              case seq: Seq[_] =>
                val nodeSeq = seq.map {
                  case n: XmlNode => n
                  case a => new Atom(a)
                }
                mountNode(parent, new Group(nodeSeq), Some(start), config)
              case a =>
                mountNode(parent, new Atom(a), Some(start), config)
            }
          } alsoCanceling (() => cancelable)


        case Some(x: XmlNode) => mountNode(parent, x, startPoint, config)
        case Some(x)          => mountNode(parent, new Atom(x), startPoint, config)
        case None             => Cancelable.empty

        case UnsafeRawHTML(rawHtml) =>
          parent.asInstanceOf[dom.html.Html].innerHTML = rawHtml
          Cancelable.empty

        case x =>
          val content = x.toString
          if (!content.isEmpty)
            parent.mountHere(dom.document.createTextNode(content), startPoint)
          Cancelable.empty
      }
    }

  private def mountMetadata(parent: DomNode, m: MetaData, v: Any, config: MountSettings): Cancelable = v match {
    case Some(x: Any) =>
      mountMetadata(parent, m, x, config)
    case r: Rx[_] =>
      val rx: Rx[_] = r
      var cancelable = Cancelable.empty
      rx.foreach { value =>
        cancelable.cancel
        cancelable = mountMetadata(parent, m, value, config)
      } alsoCanceling (() => cancelable)
    case f: Function0[Unit @ unchecked] =>
      config.inspectEvent(m.key)
      parent.setEventListener(m.key, (_: dom.Event) => f())
    case f: Function1[_, Unit @ unchecked] =>
      config.inspectEvent(m.key)
      parent.setEventListener(m.key, f)
    case _ =>
      parent.setMetadata(m, v, config)
      Cancelable.empty
  }

  private implicit class DomNodeExtra(node: DomNode) {
    def setEventListener[A](key: String, listener: A => Unit): Cancelable = {
      val dyn = node.asInstanceOf[js.Dynamic]
      dyn.updateDynamic(key)(listener)
      Cancelable(() => dyn.updateDynamic(key)(null))
    }

    def setMetadata(m: MetaData, v: Any, config: MountSettings): Unit = {
      val htmlNode = node.asInstanceOf[dom.html.Html]
      def set(key: String): Unit = v match {
        case null | None | false => htmlNode.removeAttribute(key)
        case _ =>
          config.inspectAttributeKey(key)
          val value = v match {
            case true => ""
            case _    => v.toString
          }
          if (key == "style") htmlNode.style.cssText = value
          else htmlNode.setAttribute(key, value)
      }
      m match {
        case m: PrefixedAttribute[_] => set(s"${m.pre}:${m.key}")
        case _ => set(m.key)
      }
    }

    // Creats and inserts two empty text nodes into the DOM, which delimitate
    // a mounting region between them point. Because the DOM API only exposes
    // `.insertBefore` things are reversed: at the position of the `}`
    // character in our binding example, we insert the start point, and at `{`
    // goes the end.
    def createMountSection(): (DomNode, DomNode) = {
      val start = dom.document.createTextNode("")
      val end   = dom.document.createTextNode("")
      node.appendChild(end)
      node.appendChild(start)
      (start, end)
    }

    // Elements are then "inserted before" the start point, such that
    // inserting List(a, b) looks as follows: `}` → `a}` → `ab}`. Note that a
    // reference to the start point is sufficient here. */
    def mountHere(child: DomNode, start: Option[DomNode]): Unit =
      { start.fold(node.appendChild(child))(point => node.insertBefore(child, point)); () }

    // Cleaning stuff is equally simple, `cleanMountSection` takes a references
    // to start and end point, and (tail recursively) deletes nodes at the
    // left of the start point until it reaches end of the mounting section. */
    def cleanMountSection(start: DomNode, end: DomNode): Unit = {
      val next = start.previousSibling
      if (next != end) {
        node.removeChild(next)
        cleanMountSection(start, end)
      }
    }
  }
}

private[mhtml] case class UnsafeRawHTML(rawHtml: String)

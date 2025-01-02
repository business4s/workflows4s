package workflows4s.mermaid

sealed trait MermaidElement {
  def render: String
}

case class Node(id: String, label: String, shape: Option[String] = None) extends MermaidElement {
  def render: String = shape match {
    case Some(s) => s"""$id@{ shape: $s, label: "$label"}"""
    case None    => s"$id[$label]"
  }
}

case class Link(from: String, to: String, label: Option[String] = None, prefix: "" | "x" = "", suffix: ">" | "x" = ">", midfix: "." | "" = "")
    extends MermaidElement {
  def render: String = label match {
    case Some(l) => s"$from ${prefix}-${midfix}-${suffix}|$l| $to"
    case None    => s"$from ${prefix}-${midfix}-${suffix} $to"
  }
}

case class Subgraph(id: String, name: String, elements: Seq[MermaidElement]) extends MermaidElement {
  def render: String =
    s"""subgraph $id [$name]
       |${elements.map(_.render).mkString("\n")}
       |end""".stripMargin
}

case class MermaidFlowchart(elements: Seq[MermaidElement]) {
  def render: String =
    s"""flowchart TD
       |${elements.map(_.render).mkString("\n")}
       |""".stripMargin
}

object MermaidFlowchart {
  val builder: Builder = new Builder(Nil)

  class Builder(private val elements: Seq[MermaidElement]) {
    def addNode(id: String, label: String, shape: Option[String] = None): Builder =
      new Builder(elements :+ Node(id, label, shape))

    def addLink(from: String, to: String, label: Option[String] = None, prefix: "" | "x" = "", suffix: ">" | "x" = ">"): Builder =
      new Builder(elements :+ Link(from, to, label, prefix, suffix))

    def addSubgraph(id: String, name: String)(content: Builder => Builder): Builder = {
      val subgraphBuilder = content(new Builder(Nil))
      new Builder(elements :+ Subgraph(id, name, subgraphBuilder.elements))
    }

    def addElement(element: MermaidElement)  = new Builder(elements :+ element)
    def addElements(el: Seq[MermaidElement]) = new Builder(elements ++ el)

    def build: MermaidFlowchart = MermaidFlowchart(elements)
  }
}

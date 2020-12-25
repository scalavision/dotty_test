package dotty_quill_1_1

import scala.quoted._

object SimpleQuill:
  case class Query(value: String)

  inline def query[T] = Query("  ")

object SimpleQuill1:                                                                                                        
  inline def getMyTree(inline mTree : String): String = ${ getMyTreeImpl('mTree) }
  def getMyTreeImpl(mTree: Expr[String])(using qctx: Quotes): Expr[String] =
    //import qctx.tasty.{_, given, _}
    import quotes.reflect._
    import qctx.reflect.{given, _}
    println(io.getquill.util.Messages.qprint(Term.of(mTree)))
    println(io.getquill.util.Messages.qprint(Term.of(mTree)))
    mTree

/*
object UseSimpleQuill1:
  def main(args: Array[String]): Unit =
    SimpleQuill1.getMyTree("hello".toUpperCase)
*/

/*
object CompilerKnowsAboutImplicits:
  // quoting?
  inline def classNameOf[T]: String = ${ classNameOfImpl[T] }
  def classNameOfImpl[T](implicit t: Type[T]) = {
    t.tpe.name
  }
*/


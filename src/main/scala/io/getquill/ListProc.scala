package io.getquill

import scala.quoted._

object ListProc {
  inline def index[T](inline list: List[T], index:Int): T = ${ indexImpl('list, 'index) }
  def indexImpl[T: Type](list: Expr[List[T]], index: Expr[Int])(using Quotes): Expr[T] = {
    import quotes.reflect._
    val tm = new io.getquill.parser.TastyMatchersContext
    import tm._
    val indexValue = index match { case  Const(i: Int) => i }
    val exprs = UntypeExpr(list.asTerm.underlyingArgument.asExpr) match { 
      case '{ scala.List.apply[T](${Varargs(args)}: _*) } => args  
      case _ => report.throwError("Does not match: " + Printer.TreeStructure.show(list.asTerm))
    }
    exprs.apply(indexValue)
  }

  inline def tail[T](inline list: List[T]): List[T] = ${ tailImpl('list) }
  def tailImpl[T: Type](list: Expr[List[T]])(using Quotes): Expr[List[T]] = {
    import quotes.reflect._
    val tm = new io.getquill.parser.TastyMatchersContext
    import tm._
    val exprs = UntypeExpr(list.asTerm.underlyingArgument.asExpr) match { 
      case '{ scala.List.apply[T](${Varargs(args)}: _*) } => args  
    }
    Expr.ofList(exprs.drop(1).toList)
  }

  transparent inline def isNil[T](inline list: List[T]): Boolean = ${ isNillImpl('list) }
  def isNillImpl[T: Type](list: Expr[List[T]])(using Quotes): Expr[Boolean] = {
    import quotes.reflect._
    val output =
      list match { 
        case '{ scala.List.apply[T](${Varargs(args)}: _*) } if (args.length == 0) => true
        case '{ scala.Nil } => true
        case _ => false
      }
    Literal(BooleanConstant(output)).asExprOf[Boolean]
    //Expr(output)
  }

  transparent inline def isTrue: Boolean = ${ isTrueImpl }
  def isTrueImpl(using Quotes) = {
    import quotes.reflect._
    Literal(BooleanConstant(true)).asExprOf[Boolean]
  }


  transparent inline def length[T](inline list: List[T]): Int = ${ lengthImpl('list) }
  def lengthImpl[T: Type](list: Expr[List[T]])(using Quotes): Expr[Int] = {
    import quotes.reflect._
    val output =
      list match { 
        case '{ scala.List.apply[T](${Varargs(args)}: _*) } => args.length
        case '{ scala.Nil } => 0
      }
    //'{ ${Literal(Constant(output)).asExprOf[Int]} }
    Expr(output)
  }
}

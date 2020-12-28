package io.getquill.context


import scala.language.higherKinds
import scala.language.experimental.macros
//import io.getquill.dsl.Dsl
//import io.getquill.util.Messages.fail
import java.io.Closeable
import scala.compiletime.summonFrom
import scala.util.Try
import io.getquill.{ ReturnAction }
import io.getquill.dsl.EncodingDsl
import io.getquill.quoter.Quoted
import io.getquill.quoter.QueryMeta
import io.getquill.derived._
import io.getquill.context.mirror.MirrorDecoders
import io.getquill.context.mirror.Row
import io.getquill.dsl.GenericDecoder
import io.getquill.dsl.GenericEncoder
import io.getquill.quoter.Planter
import io.getquill.quoter.EagerPlanter
import io.getquill.quoter.LazyPlanter
import io.getquill.ast.Ast
import io.getquill.ast.ScalarTag
import scala.quoted._
import io.getquill.ast.{Transform, QuotationTag}
import io.getquill.quoter.QuotationLot
import io.getquill.quoter.QuotedExpr
import io.getquill.quoter.PlanterExpr
import io.getquill.quoter.Planter
import io.getquill.idiom.ReifyStatement
import io.getquill.Query
import io.getquill.Action
import io.getquill.idiom.Idiom
import io.getquill.NamingStrategy
import io.getquill.parser.TastyMatchers

trait ContextOperation[T, D <: Idiom, N <: NamingStrategy, PrepareRow, ResultRow, Res](val idiom: D, val naming: N) {
  def execute(sql: String, prepare: PrepareRow => (List[Any], PrepareRow), extractor: Option[ResultRow => T], executionType: ExecutionType): Res
}

/**
 * Drives execution of Quoted blocks i.e. Queries etc... from the context.
 */
object QueryExecution:

  trait SummonHelper[ResultRow: Type] {
    implicit val qctx: Quotes
    import qctx.reflect._

    /** Summon decoder for a given Type and Row type (ResultRow) */
    def summonDecoderOrThrow[DecoderT: Type]: Expr[GenericDecoder[ResultRow, DecoderT]] = {
      Expr.summon[GenericDecoder[ResultRow, DecoderT]] match {
        case Some(decoder) => decoder
        case None => report.throwError("Decoder could not be summoned")
      }
    }
  }

  trait QueryMetaHelper[T: Type] extends TastyMatchers {
    import qctx.reflect.report
    // See if there there is a QueryMeta mapping T to some other type RawT
    def summonMetaIfExists =
      Expr.summon[QueryMeta[T, _]] match {
        case Some(expr) =>
          // println("Summoned! " + expr.show)
          UntypeExpr(expr) match {
            case '{ QueryMeta.apply[k, n]($one, $two, $uid) } => Some(Type.of[n])
            case _ => report.throwError("Summoned Query Meta But Could Not Get Type")
          }
        case None => None
      }
  }

  // Doesn't need to be declared as inline here because case class arguments are automatically inlined that is very cool!
  sealed trait QuotedOperation[T, Op[_]] {
    def op: Quoted[Op[T]]
  }

  // TODO Could make Quoted operation constructor that is a typeclass, not really necessary though
  object QuotedOperation {
    case class QueryOp[T](op: Quoted[Query[T]]) extends QuotedOperation[T, Query]
    case class ActionOp[T](op: Quoted[Action[T]]) extends QuotedOperation[T, Action]
  }


  class RunQuery[
    T: Type, 
    Q[_]: Type,
    ResultRow: Type, 
    PrepareRow: Type, 
    D <: Idiom: Type, 
    N <: NamingStrategy: Type, 
    Res: Type
  ](quotedOp: Expr[QuotedOperation[T, Q]], 
    ContextOperation: Expr[ContextOperation[T, D, N, PrepareRow, ResultRow, Res]])(using val qctx: Quotes) 
  extends SummonHelper[ResultRow] 
    with QueryMetaHelper[T] 
    with TastyMatchers:
    
    import qctx.reflect._

    enum ExtractBehavior:
      case Extract
      case Skip

    /** Run a query with a given QueryMeta given by the output type RawT and the conversion RawT back to T */
    def runWithMeta[RawT: Type](quoted: Expr[Quoted[Query[T]]]): Expr[Res] =
      val (queryRawT, converter, staticStateOpt) = QueryMetaExtractor.applyImpl[T, RawT, D, N](quoted)
      staticStateOpt match {
        case Some(staticState) =>
          executeStatic[RawT](staticState, converter, ExtractBehavior.Extract)
        case None => 
          executeDynamic[RawT, Query](queryRawT, converter, ExtractBehavior.Extract)
      }
    

    def executeDynamic[RawT: Type, Q[_]: Type](query: Expr[Quoted[Q[RawT]]], converter: Expr[RawT => T], extract: ExtractBehavior) =
      val decoder = summonDecoderOrThrow[RawT]
      // Is the expansion on T or RawT, need to investigate
      val expandedAst = Expander.runtimeImpl[T]('{ $query.ast })

      val prepare = '{ (row: PrepareRow) => LiftsExtractor.withLazy[PrepareRow]($query.lifts, row) }
      val extractor = extract match
        case ExtractBehavior.Extract => '{ Some( (r: ResultRow) => $converter.apply($decoder.apply(1, r)) ) }
        case ExtractBehavior.Skip =>    '{ None }

      // TODO What about when an extractor is not neededX
      '{  RunDynamicExecution.apply[RawT, T, Q, D, N, PrepareRow, ResultRow, Res]($query, $ContextOperation, $prepare, $extractor, $expandedAst) }
    

    
    def resolveLazyLifts(lifts: List[Expr[Planter[?, ?]]]): List[Expr[EagerPlanter[?, ?]]] =
      lifts.map {
        case '{ ($e: EagerPlanter[a, b]) } => e
        case '{ $l: LazyPlanter[a, b] } =>
          val tpe = l.asTerm.tpe
          tpe.asType match {
            case '[LazyPlanter[t, row]] =>
              println(s"Summoning type: ${TypeRepr.of[t].show}")
              Expr.summon[GenericEncoder[t, ResultRow]] match {
                case Some(decoder) =>
                  '{ EagerPlanter[t, ResultRow]($l.value.asInstanceOf[t], $decoder, $l.uid) }
                case None => 
                  report.throwError("Decoder could not be summoned")
              }
          }
          
          //report.throwError(s"Found Type: ${tpe.show} at ${Printer.TreeShortCode.show(l.asTerm)}", l)

          // Expr.summon[GenericDecoder[ResultRow, DecoderT]] match {
          //   case Some(decoder) => decoder
          //   case None => report.throwError("Decoder could not be summoned")
          // }
      }

    /** 
     * Execute static query via ctx.executeQuery method given we have the ability to do so 
     * i.e. have a staticState 
     */
    def executeStatic[RawT: Type](staticState: StaticState, converter: Expr[RawT => T], extract: ExtractBehavior): Expr[Res] =    
      val StaticState(query, allLifts) = staticState
      val lifts = Expr.ofList(resolveLazyLifts(allLifts))
      val decoder = summonDecoderOrThrow[RawT]

      val prepare = '{ (row: PrepareRow) => LiftsExtractor.apply[PrepareRow]($lifts, row) }
      val extractor = extract match
        case ExtractBehavior.Extract => '{ Some( (r: ResultRow) => $converter.apply($decoder.apply(1, r)) ) }
        case ExtractBehavior.Skip =>    '{ None }

      // TODO What about when an extractor is not neededX
      // executeAction(query, prepare, extractor)
      '{ $ContextOperation.execute(${Expr(query)}, $prepare, $extractor, ExecutionType.Static) }

    private val idConvert = '{ (t:T) => t }

    /** Summon all needed components and run executeQuery method */
    def applyQuery(quoted: Expr[Quoted[Query[T]]]): Expr[Res] =
      summonMetaIfExists match
        case Some(rowRepr) =>
          rowRepr match { case '[rawT] => runWithMeta[rawT](quoted) }
        case None =>
          StaticTranslationMacro.applyInner[Query, T, D, N](quoted) match 
            case Some(staticState) =>
              executeStatic[T](staticState, idConvert, ExtractBehavior.Extract)
            case None => 
              executeDynamic(quoted, idConvert, ExtractBehavior.Extract)

    def applyAction(quoted: Expr[Quoted[Action[T]]]): Expr[Res] =
      StaticTranslationMacro.applyInner[Action, T, D, N](quoted) match 
        case Some(staticState) =>
          executeStatic[T](staticState, idConvert, ExtractBehavior.Skip)
        case None => 
          executeDynamic(quoted, idConvert, ExtractBehavior.Skip)

    def apply() =
      quotedOp match
        case '{ QuotedOperation.QueryOp.apply[T]($op) } => applyQuery(op)
        case '{ QuotedOperation.ActionOp.apply[T]($op) } => applyAction(op)
        case _ => report.throwError(s"Could not match the QuotedOperation clause: ${quotedOp.show}")

  end RunQuery


  inline def apply[
    T, 
    Q[_],
    ResultRow, 
    PrepareRow, 
    D <: Idiom, 
    N <: NamingStrategy, 
    Res
  ](inline quotedOp: QuotedOperation[T, Q], ctx: ContextOperation[T, D, N, PrepareRow, ResultRow, Res]) = 
    ${ applyImpl('quotedOp, 'ctx) }
  
  def applyImpl[
    T: Type, 
    Q[_]: Type,
    ResultRow: Type, 
    PrepareRow: Type, 
    D <: Idiom: Type, 
    N <: NamingStrategy: Type, 
    Res: Type
  ](quotedOp: Expr[QuotedOperation[T, Q]], 
    ctx: Expr[ContextOperation[T, D, N, PrepareRow, ResultRow, Res]])(using qctx: Quotes): Expr[Res] =
    new RunQuery[T, Q, ResultRow, PrepareRow, D, N, Res](quotedOp, ctx).apply()



end QueryExecution

/**
 * Drives dynamic execution from the Context
 */
object RunDynamicExecution:

  import io.getquill.idiom.{ Idiom => Idiom }
  import io.getquill.{ NamingStrategy => NamingStrategy }

  def apply[
    RawT, 
    T, 
    Q[_], 
    D <: Idiom, 
    N <: NamingStrategy, 
    PrepareRow, 
    ResultRow, 
    Res
  ](quoted: Quoted[Q[RawT]], 
    ctx: ContextOperation[T, D, N, PrepareRow, ResultRow, Res],
    prepare: PrepareRow => (List[Any], PrepareRow),
    extractor: Option[ResultRow => T],
    expandedAst: Ast
  ): Res = 
  {
    // println("Runtime Expanded Ast Is: " + ast)
    val lifts = quoted.lifts // quoted.lifts causes position exception
    val quotationVases = quoted.runtimeQuotes // quoted.runtimeQuotes causes position exception

    def spliceQuotations(ast: Ast): Ast =
      Transform(ast) {
        case v @ QuotationTag(uid) => 
          // When a quotation to splice has been found, retrieve it and continue
          // splicing inside since there could be nested sections that need to be spliced
          quotationVases.find(_.uid == uid) match {
            case Some(vase) => 
              spliceQuotations(vase.quoted.ast)
            case None =>
              throw new IllegalArgumentException(s"Quotation vase with UID ${uid} could not be found!")
          }
      }

    // Splice all quotation values back into the AST recursively, by this point these quotations are dynamic
    // which means that the compiler has not done the splicing for us. We need to do this ourselves. 
    //val expandedAst = Expander.runtime[T](quoted.ast) // cannot derive a decoder for T
    val splicedAst = spliceQuotations(expandedAst)
      
    val (outputAst, stmt) = ctx.idiom.translate(splicedAst)(using ctx.naming)

    val (string, externals) =
      ReifyStatement(
        ctx.idiom.liftingPlaceholder,
        ctx.idiom.emptySetContainsToken,
        stmt,
        forProbing = false
      )

    ctx.execute(string, prepare, extractor, ExecutionType.Dynamic)
  }

end RunDynamicExecution
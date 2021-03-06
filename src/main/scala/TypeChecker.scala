/*
 * This file is part of TS0.
 * 
 * TS0 - Copyright (c) 2012, Iain McGinniss
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted under the terms of the 2-clause BSD license:
 * http://opensource.org/licenses/BSD-2-Clause
 */

package uk.ac.gla.dcs.ts0

import org.kiama._
import org.kiama.attribution.Attribution._
import scala.collection.immutable.Map.WithDefault
import org.kiama.util.Messaging._
import ObjectValidator._

object TypeChecker {

  def mSpec(mdef : MethodDef) = 
    MethodSpec(mdef.name, mdef.ret->ttype, mdef.nextState)

  def sSpec(sdef : StateDef) = 
    StateSpec(sdef.name, sdef.methods.map(mSpec _))

  val infer : Term => Option[Tuple3[Context, Type, Context]] =
    attr {
      case t => {
        val constraints = ConstraintGenerator.generateConstraints(t)
        ConstraintSolver.solve(constraints, t)
      }
    }

  def inferFunType(f : FunValue) : Option[FunType] = {
    (f->infer).map(soln => {
      val (inCtx, ty, outCtx) = soln
      ty match {
        case ft @ FunType(params, ret) =>
          ft
        case _ => throw new Error("constraint solving returned mismatched type?")
      }
    })
  }

  def inferredParamEffect(paramName : String) : FunValue => EffectType =
    attr {
      case t : FunValue => {
        val pNum = t.params.indexWhere(_.name == paramName)
        inferFunType(t).map(ft => {
          ft.params(pNum)
        }).getOrElse(ErrorType() >> ErrorType())
      }
    }

  /** determines the effect type for a parameter */
  val pEffect : ParamDef => EffectType =
    childAttr {
      case p @ ParamDef(name, typeInfo) => {
        case f @ FunValue(params, body) => 
          typeInfo getOrElse (f->inferredParamEffect(name))
      }
    }

  val fBodyType : FunValue => Type =
    attr {
      case f @ FunValue(params, body) => {
        if(fullyTypedFunction(f)) body->ttype
        else {
          inferFunType(f).map(ft => ft.ret).getOrElse(ErrorType())
        }
      }
    }

  def fullyTypedFunction(f : FunValue) = {
    f.params.forall(_.typeInfo.isDefined)
  }

  /**
   * Determines the type for a given term.
   */
  val ttype : Term => Type =
    attr {
      case UnitValue() => UnitType()
      case o @ ObjValue(states,state) => {
        val objErrors = o->objectErrors
        if(objErrors.isEmpty) {
          ObjType(states.map(sSpec _), state)
        } else {
          objErrors.foreach(err => 
            message(err.refPoint, "missing state: " + err.state))
          ErrorType()
        }
      }
      case FunValue(params,body) => 
        FunType(params.map(_->pEffect), body->ttype)
      case LetBind(_,_,body) => body->ttype
      case Update(_,_) => UnitType()
      case Sequence(l,r) => r->ttype
      case t @ MethCall(o,m) => 
        ((t->input)(o)) match { 
          case o @ ObjType(_,_) => o.retType(m) 
          case _ => ErrorType()
        }
      case t @ FunCall(name, params) =>
        ((t->input)(name)) match {
          case FunType(_,retType) => retType
          case _ => ErrorType()
        }
      case ErrorValue() => ErrorType()
    }

  /** 
   * constructs an input context for a set of parameters defined on a function
   * literal.
   */
  def contextFromParams(params : Seq[ParamDef]) =
    (params.foldLeft
      (Map.empty[String,Type])
      ((map,param) => map + Pair(param.name,(param->pEffect).before)))
    
  /** determines the input context for a term */
  val input : Term => Context =
    childAttr {
      case t => {
        case FunValue(params,body) => contextFromParams(params)
        case p @ LetBind(name,value,body) => {
          if (t eq value) p->input
          else value->output + Pair(p.varName,p.value->ttype)
        }
        case p @ Update(varName,_) => {
          val ctx = (p->input) - varName
          ctx
        }
        case p @ Sequence(left, right) => 
          (if (t eq left) p->input else p.left->output)
        case other => {
          println("unmatched case " + other + " --- " + t)
          emptyContext
        }
      }
    }

  /**
   * using the derived input context for a term, determines the type of
   * a particular variable in that context if it is known.
   */
  def inputType(t : Term, varName : String) = (t->input) get varName

  /**
   * determines the output context for a term.
   */
  val output : Term => Context =
    attr {
      case t : Value => t->input
      case LetBind(varName,valTerm,bodyTerm) => bodyTerm->output - varName
      case Update(varName,body) => body->output + Pair(varName, body->ttype)
      case t @ MethCall(objName,methName) => methCallOutput(t)
      case Sequence(l,r) => r->output
      case t @ FunCall(funName,params) => funCallOutput(t)
    } 

  def methCallOutput(t : MethCall) : Context = {
    val newType = inputType(t, t.objVarName) match {
      case None => {
        message(t, "attempt to use unknown variable %s as a method call receiver".format(t.objVarName))
        ErrorType()
      }
      case Some(o @ ObjType(states,_)) => {
        val nextState = o.currentState flatMap (s => s.nextState(t.methName))
        nextState match {
          case None => {
            message(t, "attempt to call unavailable method %s on receiver %s of type %s".format(t.methName, t.objVarName, o))
            ErrorType()
          }
          case Some(state) => {
            ObjType(states, state)
          }
        }
      }
      case Some(ErrorType()) => ErrorType()
      case Some(x) => {
        message(t, "attempt to call method on variable %s of type %s".format(t.objVarName, x))
        ErrorType()
      }
    }

    (t->input).updated(t.objVarName, newType)
  }

  def funCallOutput(t : FunCall) : Context = {
    val newParamTypes = inputType(t, t.funName) match {
      case Some(x) => x match {
        case FunType(defParams,_) => {
          val paramTypes = t.paramNames.map(param => {
            val ty = inputType(t, param) match {
              case Some(ty) => ty
              case None => {
                message(t, "unknown parameter variable " + param)
                ErrorType()
              }
            }

            (param, ty)
          })
          defParams.zip(paramTypes).map(p => {
            val (effectType, param) = p
            val (paramName, paramType) = param
            if(effectType.before != paramType && paramType != ErrorType()) {
              message(t, "parameter " + paramName 
                + " is not of the required type for function " +
                t.funName + ": expected " + effectType.before +
                ", but found " + paramType)
            }
            // always just produce the output type as defined on the effect
            // as this may allow more errors to be found in the program
            effectType.after
          })
        }
        case ErrorType() => t.paramNames.map(_ => ErrorType())
        case x => {
          message(t, "attempt to treat variable %s as a function when it is of type %s".format(t.funName, x))
          t.paramNames.map(_ => ErrorType())
        }
      }
      case None => {
        message(t, "attempt to use unknown variable %s as a function".format(t.funName))
        t.paramNames.map(_ => ErrorType())
      }
    }

    (t->input -- t.paramNames) ++ (t.paramNames zip newParamTypes)
  }

  def check(t : Term) {
    org.kiama.attribution.Attribution.initTree(t)
    t->output
  }
}

import jline.Terminal
import jline.TerminalFactory

class FullTracePrinter(term : Terminal) extends org.kiama.output.PrettyPrinter {

  import TypeChecker._

  override val defaultIndent = 2

  var nextTermId = 0

  val termId : Term => Doc =
    attr {
      case _ : Term => {
        nextTermId += 1
        TID(nextTermId.toString())
      }
    }

  def resetTermIds = {
    nextTermId = 0
  }

  def pretty (t : Term) : String = super.pretty(show(t), term.getWidth())

  def show(t : Term) : Doc = {
    val tid = t->termId
    val children = Seq.empty[Doc]
    val (doc, terms) = t match {
      case UnitValue() => 
        Pair(UNIT, Seq.empty)
      case ObjValue(states, state) => {
        val sDocs = states map (showStateDef _)
        val objBody = nest(lsep(sDocs, space))
        val objDoc = brackets(objBody <> line) <> "@" <> state
        val subTerms = states flatMap (_.methods map (_.ret))
        Pair(objDoc, subTerms)
      }
      case FunValue(params, body) => {
        val pDocs = params map showParams
        val paramDoc = group("\\" <> parens(ssep(pDocs, ",")))
        val bodyTermId = body->termId
        val funDoc = paramDoc <> "." <> parens(bodyTermId)
        Pair(funDoc, Seq(body))
      }
      case LetBind(varName, value, body) => {
        val valueTermId = value->termId
        val bodyTermId = body->termId
        val termDoc = 
          LET <+> varName <+> "=" <+> valueTermId <+> IN <+> bodyTermId
        Pair(termDoc, Seq(value, body))
      }
      case Update(varName, body) => { 
        val bodyTermId = body->termId
        Pair(varName <+> ":=" <+> (bodyTermId), Seq(body))
      }
      case MethCall(objVarName, methName) => 
        Pair(objVarName <> "." <> methName, Seq.empty)
      case Sequence(left, right) => {
        val leftTermId = left->termId
        val rightTermId = right->termId
        Pair(leftTermId <> ";" </> rightTermId, Seq(left, right))
      }
        
      case FunCall(funName, paramNames) => 
        Pair(funName <> parens(lsep(paramNames map (text _), ",")), Seq.empty)
    }
    val termWithType = doc <+> ":" <+> TYPE(showType(t->ttype))
    val tidDoc = tid <> ":"
    val (inContext, outContext) = showContexts(t->input, t->output)

    val docWithContexts = tidDoc <+> group(inContext </> termWithType </> outContext)

    docWithContexts </> nest(lsep(terms.map(show _), " "))
  }

  val showParams : ParamDef => Doc = (p => 
    p.name <+> ":" <+> 
      ((p.typeInfo map showEffect) getOrElse empty))

  val showEffect : EffectType => Doc =
    eff => showType(eff.before) <+> ">>" <+> showType(eff.after)

  def showStateDef(s : StateDef) : Doc = {
    val mDocs = s.methods map showMethodDef
    s.name <+> braces(space <> nest(lsep(mDocs, ",")) <> line)
  }

  val showMethodDef : MethodDef => Doc = m => {
    val tid = m.ret->termId
    (m.name <+> "=" <+> parens(tid <+> "," </> m.nextState))
  }
    

  def showType(t : Type) : Doc =
  t match {
    case UnitType() => "Unit"
    case FunType(params, ret) => {
      val pDocs = params map showEffect
      parens(ssep(pDocs, ",")) <+> "->" <+> showType(ret)
    }
    case ErrorType() => "BAD"
    case ObjType(states, state) => {
      val sDocs = states map (showStateSpec _)
      braces(space <> nest(fillsep(sDocs, space)) <> line) <> "@" <> state
    }
  }

  def showStateSpec(s : StateSpec) = {
    val mDocs = s.methods map showMethodSpec
    s.name <+> braces(nest(lsep(mDocs, ";")) </> empty)
  }

  val showMethodSpec : MethodSpec => Doc =
    (m => m.name <+> ":" <+> showType(m.ret) <+> ">>" <+> m.nextState)

  def showContexts(in : Context, out : Context) : Pair[Doc, Doc] = {
    val commonVars = in.keySet.intersect(out.keySet)
    val (unchanged, changed) = commonVars.partition(v => in(v) == out(v))

    val deletedVars = in.filterKeys(in.keySet -- commonVars)
    val newVars = out.filterKeys(out.keySet -- commonVars)
    val unchangedVars = in.filterKeys(unchanged)
    val inChangedVars = in.filterKeys(changed)
    val outChangedVars = out.filterKeys(changed)

    val valFolder : String => (Seq[Doc],Pair[String,Type]) => Seq[Doc] = 
      colorStr => (_ :+ showVarMapping(_, colorStr))
    val docFolder : (String,Map[String,Type]) => Seq[Doc] =
      (colorStr, doc) => doc.foldLeft(Seq.empty[Doc])(valFolder(colorStr))
    
    val unchangedDocs = docFolder(Console.BLACK, unchangedVars)
    val inChangedDocs = docFolder(Console.YELLOW, inChangedVars)
    val outChangedDocs = docFolder(Console.YELLOW, outChangedVars)

    val deletedDocs = docFolder(Console.RED, deletedVars)
    val newDocs = docFolder(Console.GREEN, newVars)

    val docProducer = 
      (docs : Seq[Doc]) => group(brackets(nest(fillsep(docs, ","))))

    val inDoc = docProducer(unchangedDocs ++ inChangedDocs ++ deletedDocs)
    val outDoc = docProducer(unchangedDocs ++ outChangedDocs ++ newDocs)

    Pair(inDoc, outDoc)
  }

  def showVarMapping(p : Pair[String,Type], colorStr : String) : Doc = 
    color(colorStr)(p._1 <+> ":" <+> showType(p._2))

  val color : String => Doc => Doc =
    (if(term.isAnsiSupported())
      ((colorStr:String) => (d:Doc) => colorStr <> d <> Console.RESET)
    else (_ => (d:Doc) => d))
  
  val VALUE : String => Doc = color(Console.BLUE)(_)
  val KEYWORD : String => Doc = color(Console.MAGENTA)(_)
  val TID : String => Doc = color(Console.BOLD)(_)
  val TYPE : Doc => Doc = color(Console.CYAN)

  val UNIT = VALUE("unit")
  val LET = KEYWORD("let")
  val IN = KEYWORD("in")
}

object FullTracePrinter extends FullTracePrinter(TerminalFactory.create())
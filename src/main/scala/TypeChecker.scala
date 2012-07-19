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

object TypeChecker {

  /** A typing context, which consists of variable names mapped to types */
  type Context = Map[String,Type]

  /** an empty context, which maps all variable names to ErrorType */
  val emptyContext : Context = Map.empty withDefaultValue ErrorType()

  def mSpec(mdef : MethodDef) = 
    MethodSpec(mdef.name, mdef.ret->ttype, mdef.nextState)

  def sSpec(sdef : StateDef) = 
    StateSpec(sdef.name, sdef.methods.map(mSpec _))

  // TODO: not implemented at all yet
  def inferredParamEffect(paramName : String) : FunValue => EffectType =
    attr {
      case t : FunValue => {
        message(t, "Type inference required for this function, not implemented")
        EffectType(UnitType(), UnitType())
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

  /**
   * Determines the type for a given term.
   */
  val ttype : Term => Type =
    attr {
      case UnitValue() => UnitType()
      case ObjValue(states,state) => ObjType(states.map(sSpec _), state)
      case FunValue(params,body) => FunType(params.map(_->pEffect), body->ttype)
      case LetBind(_,_,_) => UnitType()
      case Update(_,_) => UnitType()
      case Sequence(l,r) => r->ttype
      case t @ MethCall(o,m) => 
        ((t->input)(o)) match { case o @ ObjType(_,_) => o.retType(m) }
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
        case p @ LetBind(name,value,body) => 
          (if (t == value) p->input 
           else value->output + Pair(p.varName,p.value->ttype))
        case p @ Update(_,_) => p->input
        case p @ Sequence(left, right) => 
          (if (t == left) p->input else p.left->output)
        case _ => emptyContext
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
          // TODO: check input param types match declared function param types
          defParams.map(_.after)
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
    t->output
  }
}

import jline.Terminal
import jline.TerminalFactory

class FullTracePrinter(term : Terminal) extends org.kiama.output.PrettyPrinter {

  import TypeChecker._

  def pretty (t : Term) : String = super.pretty(show(t), term.getWidth())

  def show(t : Term) : Doc = {
    t match {
      case UnitValue() => UNIT
      case ObjValue(states, state) => {
        val sDocs = states map (showStateDef _)
        brackets(nest(lsep(sDocs, space))) <> "@" <> state
      }
      case FunValue(params, body) => {
        val pDocs = params map showParams
        val paramDoc = group("\u30BB" <> parens(ssep(pDocs, ",")))

        paramDoc <> "." <> parens(nest(show(body)))
      }
      case LetBind(varName, value, body) => {
        LET <+> varName <+> "=" <+> nest(show(value)) <+> IN </> show(body)
      }
      case Update(varName, body) => varName <+> ":=" <+> nest(show(body))
      case MethCall(objVarName, methName) => objVarName <> "." <> methName
      case Sequence(left, right) => show(left) <> ";" </> show(right)
      case FunCall(funName, paramNames) => 
        funName <> parens(lsep(paramNames map (text _), ","))
    }
  }
    

  val showParams : ParamDef => Doc = (p => 
    p.name <> ((p.typeInfo map (space <+> showEffect(_)) getOrElse empty)))

  val showEffect : EffectType => Doc =
    eff => showType(eff.before) <+> ">>" <+> showType(eff.after)

  def showStateDef(s : StateDef) : Doc = {
    val mDocs = s.methods map showMethodDef
    s.name <+> braces(nest(lsep(mDocs, ",")))
  }

  val showMethodDef : MethodDef => Doc =
    m => (m.name <+> "=" <+> parens((show (m.ret)) <+> "," </> m.nextState))

  def showType(t : Type) : Doc =
  t match {
    case UnitType() => "unit"
    case FunType(params, ret) => {
      val pDocs = params map showEffect
      parens(ssep(pDocs, ",")) <+> "\u2192" <+> showType(ret)
    }
    case ErrorType() => "BAD"
    case ObjType(states, state) => {
      val sDocs = states map (showStateSpec _)
      brackets(nest(lsep(sDocs, space))) <> "@" <> state
    }
  }

  def showStateSpec(s : StateSpec) = {
    val mDocs = s.methods map showMethodSpec
    s.name <+> brackets(nest(lsep(mDocs, ";")))
  }

  val showMethodSpec : MethodSpec => Doc =
    (m => m.name <+> ":" <+> showType(m.ret) <+> "\u226B" <+> m.nextState)

  val color : String => String => String =
    (if(term.isAnsiSupported())
      ((colorStr:String) => (s:String) => colorStr + s + Console.RESET)
    else (_ => (s:String) => s))
  
  val VALUE : String => String = color(Console.BLUE) 
  val KEYWORD : String => String = color(Console.MAGENTA)

  val UNIT = text(VALUE("unit"))
  val LET = text(KEYWORD("let"))
  val IN = text(KEYWORD("in"))
}

object FullTracePrinter extends FullTracePrinter(TerminalFactory.create())
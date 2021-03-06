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
import org.kiama.attribution.Attributable

object ConstraintGenerator {
  
  def sameAs(base : ContextVar) = ModifiedContext(base, Map.empty)
  def removeFrom(base : ContextVar, removedVar : String) =
    ContextRemoval(base, removedVar)
  def addTo(base : ContextVar, varName : String, varType : TypeExpr) = 
    ContextAddition(base, Map(varName -> varType))
  def update(base : ContextVar, varName : String, varType : TypeExpr) =
    ModifiedContext(base, Map(varName -> varType))

  val root : Attributable => Attributable =
    attr {
      case t : Attributable => if(t.isRoot) t else (t.parent)->root
    }

  val tvGen : Attributable => TypeVarGenerator =
    attr {
      case a : Attributable => 
        if(a.isRoot) new TypeVarGenerator() else (a->root)->tvGen
    }

  val cvGen : Attributable => ContextVarGenerator =
    attr {
      case a : Attributable => 
        if(a.isRoot) new ContextVarGenerator() else (a->root)->cvGen
    }

  val typeVar : Term => TypeVar =
    attr {
      case t : Term => (t->tvGen).next()
    }

  val inContextVar : Term => ContextVar =
    attr {
      case t : Term  => (t->cvGen).next()
    }

  val outContextVar : Term => ContextVar =
    attr {
      case t : Term  => (t->cvGen).next()
    }

  val objVar : ObjValue => TypeVar =
    attr {
      case o : ObjValue => (o->tvGen).next()
    }

  val stateVar : StateDef => TypeVar =
    attr {
      case s : StateDef => (s->tvGen).next()
    }

  val objInitStateVar : ObjValue => TypeVar =
    attr {
      case o : ObjValue => (o.stateMap(o.state))->stateVar
    }

  def generateConstraints(t : Term) : ConstraintSet = {
    (t->tvGen).reset()
    (t->cvGen).reset()

    (ConstraintSet.empty +
    ContextConstraint(t->inContextVar, BaseContext(Map.empty, true)) ++
    allConstraints(t))
  }
    

  def allConstraints(t : Term) : ConstraintSet =
    (t->constraints) ++ 
    (t match {
      case UnitValue() => ConstraintSet.empty
      case o @ ObjValue(states,_) => methodConstraints(o)
      case FunValue(_,body) => allConstraints(body)
      case LetBind(_,value,body) => 
        allConstraints(value) ++ allConstraints(body)
      case Update(_, body) => allConstraints(body)
      case MethCall(_,_) => ConstraintSet.empty
      case Sequence(left, right) => 
        allConstraints(left) ++ allConstraints(right)
      case FunCall(_,_) => ConstraintSet.empty
    })

  def methodConstraints(o : ObjValue) =
    (o.states.foldLeft
      (ConstraintSet.empty)
      ((cs, state) => {
        val sv = state->stateVar
        (state.methods.foldLeft
          (cs)
          ((cs, m) => (cs ++
              ((m.ret)->constraints) +
              ContextConstraint(
                (m.ret)->inContextVar, 
                sameAs(o->inContextVar)) +
              ContextConstraint(
                (m.ret)->outContextVar, 
                sameAs((m.ret)->inContextVar))
            )
          )
        )
      })
    )

  val constraints : Term => ConstraintSet =
    attr {
      case t : UnitValue => unitValueConstraints(t)
      case t : ObjValue  => objectValueConstraints(t)
      case t : FunValue  => funValueConstraints(t)
      case t : LetBind   => letBindConstraints(t)
      case t : Update    => updateConstraints(t)
      case t : Sequence  => sequenceConstraints(t)
      case t : FunCall   => funCallConstraint(t)
      case t : MethCall  => methCallConstraint(t)
    }

  def unitValueConstraints(t : UnitValue) =
    (ConstraintSet.empty +
      TypeExprConstraint(VarTE(t->typeVar), UnitTE) +
      ContextConstraint(t->outContextVar, sameAs(t->inContextVar)))
    

  def objectValueConstraints(t : ObjValue) = {
    (ConstraintSet.empty +
      ContextConstraint(t->outContextVar, sameAs(t->inContextVar)) +
      TypeExprConstraint(
        VarTE(t->typeVar), createSolvedObject(t))
    )
  }

  def createSolvedObject(o : ObjValue) = {
    val stateMap = 
      (o.states.foldLeft
        (Map.empty[String,StateDef])
        ((m, s) => m + (s.name -> s))
      )

    val getStateVar = ((name : String) => 
      stateMap.get(name).map(state => state->stateVar).getOrElse {
      throw new IllegalArgumentException(
            "state " + name + "missing")
    })

    SolvedObjectTE(
      o->objVar,
      o.states.map(state => {
        StateTE(state->stateVar, state.methods.map(m => {
          MethodTE(
            m.name, 
            VarTE((m.ret)->typeVar), 
            getStateVar(m.nextState)
          )
        }))
      }),
      o->objInitStateVar
    )
  }

  def funValueConstraints(t : FunValue) = {
    val inTypeVars = t.params.map(paramToTypeExpr)
    val outTypeVars = t.params.map(paramToTypeExpr)
    val effects : Seq[EffectTE] = buildEffects(inTypeVars, outTypeVars)
    val funType = FunTE(effects, VarTE(t.body->typeVar))

    val outTypeConstraints =
      (outTypeVars.foldLeft
        (ConstraintSet.empty)
        ((c,p) => 
          c + ContextVarConstraint(t.body->outContextVar, p._1, p._2)))

    outTypeConstraints ++ (ConstraintSet.empty +
      ContextConstraint(t->outContextVar, sameAs(t->inContextVar)) +
      ContextConstraint(t.body->inContextVar, 
        BaseContext(Map(inTypeVars: _*), false)) +
      TypeExprConstraint(VarTE(t->typeVar), funType))
  }

  def letBindConstraints(t : LetBind) =
    (ConstraintSet.empty +
      ContextConstraint(t.value->inContextVar, sameAs(t->inContextVar)) +
      ContextConstraint(t.body->inContextVar, 
        addTo(t.value->outContextVar, t.varName, VarTE(t.value->typeVar))) +
      ContextConstraint(t->outContextVar, 
        ContextRemoval(t.body->outContextVar, t.varName)) +
      TypeExprConstraint(VarTE(t->typeVar), VarTE(t.body->typeVar))
    )

  def updateConstraints(t : Update) = {
    val bodyType = VarTE(t.body->typeVar)
    (ConstraintSet.empty +
      ContextConstraint(t.body->inContextVar, 
        removeFrom(t->inContextVar, t.varName)) +
      ContextConstraint(t->outContextVar, 
        addTo(t.body->outContextVar, t.varName, bodyType)) +
      TypeExprConstraint(VarTE(t->typeVar), UnitTE)
    )
  }

  def sequenceConstraints(t : Sequence) =
    (ConstraintSet.empty +
      ContextConstraint(t.left->inContextVar, sameAs(t->inContextVar)) +
      ContextConstraint(t.right->inContextVar, sameAs(t.left->outContextVar)) +
      ContextConstraint(t->outContextVar, sameAs(t.right->outContextVar)) +
      TypeExprConstraint(VarTE(t->typeVar), VarTE(t.right->typeVar))
    )

  def funCallConstraint(t : FunCall) = {
    val paramTypeVars = 
      t.paramNames.map(p => Tuple3(p, (t->tvGen).next(), (t->tvGen).next()))
    val funEffects = paramTypeVars.map(t => EffectTE(VarTE(t._2), VarTE(t._3)))
    val funRetTypeVar = (t->tvGen).next()
    val funType = FunTE(funEffects, VarTE(funRetTypeVar))

    val paramConstraints = 
      (paramTypeVars.foldLeft
        (ConstraintSet.empty)
        ((c, p) => c + ContextVarConstraint(t->inContextVar, p._1, VarTE(p._2)))
      )

    val contextChangedVars =
      (paramTypeVars.foldLeft
        (Map.empty[String,TypeExpr])
        ((m,p) => m + (p._1 -> VarTE(p._3))))

    (paramConstraints ++
      (ConstraintSet.empty +
        ContextVarConstraint(t->inContextVar, t.funName, funType) +
        ContextConstraint(t->outContextVar, 
          ModifiedContext(t->inContextVar, contextChangedVars)) +
        TypeExprConstraint(VarTE(t->typeVar), VarTE(funRetTypeVar)))
    )
  }

  def methCallConstraint(t : MethCall) = {
    val objectVar = (t->tvGen).next()
    val inStateVar = (t->tvGen).next()
    val outStateVar = (t->tvGen).next()
    val inObjectType = ObjectTE(objectVar, inStateVar)
    val outObjectType = ObjectTE(objectVar, outStateVar)
    val methType = VarTE(t->typeVar)
    (ConstraintSet.empty +
      ContextVarConstraint(t->inContextVar, t.objVarName, inObjectType) +
      MethodConstraint(objectVar, inStateVar, t.methName, 
        methType, outStateVar) +
      ContextConstraint(t->outContextVar, 
        ModifiedContext(t->inContextVar, Map(t.objVarName -> outObjectType)))
    )
  }

  def buildEffects(in : Seq[(String,TypeExpr)], out : Seq[(String,TypeExpr)]) =
    in.zip(out).map(x => EffectTE(x._1._2, x._2._2))

  val paramToTypeExpr = 
    (pdef : ParamDef) => Pair(pdef.name, VarTE((pdef->tvGen).next()))
}
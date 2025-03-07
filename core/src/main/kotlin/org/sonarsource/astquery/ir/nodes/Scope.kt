/*
 * Copyright (C) 2018-2025 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarsource.astquery.ir.nodes

import org.sonarsource.astquery.graph.ScopeId
import org.sonarsource.astquery.graph.TranslationTable
import org.sonarsource.astquery.graph.visual.FlowType
import org.sonarsource.astquery.graph.visual.VisualInfo

var nextScopeId = 0

class Scope<IN>(
  parent: ParentNode<IN>,
  val scopeId: ScopeId = nextScopeId++
) : IRNode<IN, IN>(parent) {

  override val isSink = false

  override fun canMergeWith(other: IRNode<*, *>): Boolean =
    other is Scope<*> && scopeId == other.scopeId

  val unscopes = mutableSetOf<Unscope<*>>()

  override fun copy() = Scope(parents.single(), scopeId).also { scope ->
    unscopes.forEach { unScope ->
      scope.unscopes += unScope
      unScope.addScopeStart(scope)
    }
  }

  override fun applyTranslation(table: TranslationTable) {
    super.applyTranslation(table)

    // For every unscope that has a translation, remove this scope from it and add itself to the translated unscope
    unscopes
      .filter(table::hasTranslation)
      .toList()
      .forEach { unScope ->
        unscopes -= unScope
        unScope.scopeStarts -= this

        val newUnscope = table.get(unScope) as Unscope<*>
        unscopes += newUnscope
        newUnscope.addScopeStart(this)
      }
  }

  override fun delete() {
    unscopes.forEach { it.scopeStarts -= this }
    super.delete()
  }

  override fun getFlowType(parentsInfo: Map<IRNode<*, *>, VisualInfo>): FlowType {
    return FlowType.Single
  }

  override fun toString() = "Scope($scopeId-${unscopes.map(IRNode<*, *>::id).joinToString()})"
}

class Unscope<IN>(
  parent: ParentNode<IN>,
  scopeParents: Set<Scope<*>>
) : IRNode<IN, IN>(parent) {

  constructor(parent: ParentNode<IN>, vararg scopeParents: Scope<*>) : this(parent, scopeParents.toSet())

  init {
    require(scopeParents.isNotEmpty()) { "UnScope must have at least one scope parent" }
    scopeParents.forEach { it.unscopes += this }
  }

  override val isSink = false

  override fun canMergeWith(other: IRNode<*, *>): Boolean =
    other is Unscope<*> && scopeStarts == other.scopeStarts

  var scopeStarts = scopeParents

  fun addScopeStart(scope: Scope<*>) {
    scopeStarts += scope
  }

  override fun copy(): Unscope<IN> {
    return Unscope(parents.single(), scopeStarts)
  }

  override fun applyTranslation(table: TranslationTable) {
    super.applyTranslation(table)

    // For every scope that has a translation, remove this unscope from it and add itself to the translated scope
    scopeStarts
      .filter(table::hasTranslation)
      .toList()
      .forEach { scope ->
        scopeStarts -= scope
        scope.unscopes -= this

        val newScope = table.get(scope) as Scope<*>
        addScopeStart(newScope)
        scope.unscopes += this
      }
  }

  override fun delete() {
    scopeStarts.forEach { it.unscopes -= this }
    super.delete()
  }

  override fun getFlowType(parentsInfo: Map<IRNode<*, *>, VisualInfo>): FlowType {
    if (scopeStarts.any { scope -> !parentsInfo.containsKey(scope) }) {
      return FlowType.ERR
    }

    val scopeStartsFlows = scopeStarts.map { parentsInfo.getValue(it.parents.single()).flowType }
    val scopeInFlow = scopeStartsFlows.reduce { acc, flow -> acc + flow }

    val parentInFlow = getParentFlowType(parentsInfo)

    return scopeInFlow + parentInFlow
  }

  override fun secondaryVisualLinks(parentsInfo: Map<IRNode<*, *>, VisualInfo>) =
    if (scopeStarts.any { scope -> !parentsInfo.containsKey(scope) }) {
      setOf("err")
    } else {
      scopeStarts.map { parentsInfo.getValue(it).id }.toSet()
    }

  override fun toString() = "UnScope(${scopeStarts.map(IRNode<*, *>::id).joinToString()})"
}

/*
 * GRAKN.AI - THE KNOWLEDGE GRAPH
 * Copyright (C) 2018 Grakn Labs Ltd
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package grakn.core.graql.reasoner.atom;

import grakn.core.concept.answer.ConceptMap;
import grakn.core.graql.executor.property.PropertyExecutor;
import grakn.core.graql.reasoner.atom.predicate.IdPredicate;
import grakn.core.graql.reasoner.atom.predicate.NeqValuePredicate;
import grakn.core.graql.reasoner.atom.predicate.ValuePredicate;
import grakn.core.graql.reasoner.query.ReasonerQuery;
import grakn.core.graql.reasoner.utils.ReasonerUtils;
import graql.lang.Graql;
import graql.lang.pattern.Conjunction;
import graql.lang.property.HasAttributeProperty;
import graql.lang.property.ValueProperty;
import graql.lang.statement.Statement;
import graql.lang.statement.Variable;

import javax.annotation.CheckReturnValue;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Factory class for creating {@link Atomic} objects.
 */
public class AtomicFactory {

    /**
     * @param pattern conjunction of patterns to be converted to atoms
     * @param parent query the created atoms should belong to
     * @return set of atoms
     */
    public static Stream<Atomic> createAtoms(Conjunction<Statement> pattern, ReasonerQuery parent) {
        Set<Atomic> atoms = pattern.statements().stream()
                .flatMap(statement -> statement.properties().stream()
                        .map(property -> PropertyExecutor.create(statement.var(), property)
                                .atomic(parent, statement, pattern.statements()))
                        .filter(Objects::nonNull))
                .collect(Collectors.toSet());

        //extract neqs from attributes
        pattern.statements().stream()
                .flatMap(statement -> statement.getProperties(HasAttributeProperty.class))
                .flatMap(hp -> hp.statements().flatMap(statement ->
                        statement.getProperties(ValueProperty.class)
                                .map(property -> PropertyExecutor.create(statement.var(), property)
                                        .atomic(parent, statement, pattern.statements()))
                                .filter(Objects::nonNull))
                ).forEach(atoms::add);

        //remove duplicates
        return atoms.stream()
                .filter(at -> atoms.stream()
                        .filter(Atom.class::isInstance)
                        .map(Atom.class::cast)
                        .flatMap(Atom::getInnerPredicates)
                        .noneMatch(at::equals)
                );
    }

    /**
     * @param parent query context
     * @return (partial) set of predicates corresponding to this answer
     */
    @CheckReturnValue
    public static Set<Atomic> answerToPredicates(ConceptMap answer, ReasonerQuery parent) {
        Set<Variable> varNames = parent.getVarNames();
        return answer.map().entrySet().stream()
                .filter(e -> varNames.contains(e.getKey()))
                .map(e -> IdPredicate.create(e.getKey(), e.getValue().id(), parent))
                .collect(Collectors.toSet());
    }

    public static Atomic createValuePredicate(ValueProperty property, Statement statement, Set<Statement> otherStatements,
                                              boolean allowNeq, boolean attributeCheck, ReasonerQuery parent) {
        HasAttributeProperty has = statement.getProperties(HasAttributeProperty.class).findFirst().orElse(null);
        Variable var = has != null? has.attribute().var() : statement.var();
        ValueProperty.Operation directOperation = property.operation();
        Variable predicateVar = directOperation.innerStatement() != null? directOperation.innerStatement().var() : null;

        boolean buildNeq = directOperation.comparator().equals(Graql.Token.Comparator.NEQV);
        boolean partOfAttribute = otherStatements.stream()
                .flatMap(s -> s.getProperties(HasAttributeProperty.class))
                .anyMatch(p -> p.attribute().var().equals(var));
        boolean hasParentVp =
                otherStatements.stream()
                        .flatMap(s -> s.getProperties(ValueProperty.class))
                        .map(vp -> vp.operation().innerStatement())
                        .filter(Objects::nonNull)
                        .map(Statement::var)
                        .anyMatch(pVar -> pVar.equals(var));
        if (hasParentVp) return null;
        if (attributeCheck && (partOfAttribute && !buildNeq)) return null;

        ValueProperty.Operation indirectOperation = ReasonerUtils.findValuePropertyOp(predicateVar, otherStatements);
        ValueProperty.Operation operation = indirectOperation != null? indirectOperation : directOperation;
        Object value = operation.innerStatement() == null? operation.value() : null;
        return buildNeq?
                (allowNeq?
                        NeqValuePredicate.create(var.asUserDefined(), predicateVar, value, parent) :
                        ValuePredicate.neq(var.asUserDefined(), predicateVar, value, parent)) :
                ValuePredicate.create(var.asUserDefined(), operation, parent);
    }
}


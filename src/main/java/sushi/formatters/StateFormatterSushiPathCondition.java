package sushi.formatters;

import static jbse.common.Type.className;
import static jbse.common.Type.isReference;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import jbse.common.Type;
import jbse.common.exc.UnexpectedInternalException;
import jbse.mem.Clause;
import jbse.mem.ClauseAssume;
import jbse.mem.ClauseAssumeAliases;
import jbse.mem.ClauseAssumeExpands;
import jbse.mem.ClauseAssumeNull;
import jbse.mem.ClauseAssumeReferenceSymbolic;
import jbse.mem.Objekt;
import jbse.mem.State;
import jbse.val.Any;
import jbse.val.Expression;
import jbse.val.FunctionApplication;
import jbse.val.NarrowingConversion;
import jbse.val.Operator;
import jbse.val.Primitive;
import jbse.val.PrimitiveSymbolic;
import jbse.val.PrimitiveVisitor;
import jbse.val.ReferenceSymbolic;
import jbse.val.Simplex;
import jbse.val.Symbolic;
import jbse.val.Term;
import jbse.val.WideningConversion;

/**
 * A {@link Formatter} used by Sushi (check of path condition
 * clauses).
 * 
 * @author Pietro Braione
 */
public final class StateFormatterSushiPathCondition implements FormatterSushi {
	private final long methodNumber;
	private final Supplier<Long> traceCounterSupplier;
	private final Supplier<State> initialStateSupplier;
	private StringBuilder output = new StringBuilder();
	private int testCounter = 0;

	public StateFormatterSushiPathCondition(long methodNumber,
			                                Supplier<Long> traceCounterSupplier,
			                                Supplier<State> initialStateSupplier) {
		this.methodNumber = methodNumber;
		this.traceCounterSupplier = traceCounterSupplier;
		this.initialStateSupplier = initialStateSupplier;
	}

	public StateFormatterSushiPathCondition(int methodNumber,
			                                Supplier<State> initialStateSupplier) {
		this(methodNumber, null, initialStateSupplier);
	}

	@Override
	public void formatPrologue() {
		this.output.append(PROLOGUE_1);
		this.output.append('_');
		this.output.append(this.methodNumber);
		if (this.traceCounterSupplier != null) {
			this.output.append('_');
			this.output.append(this.traceCounterSupplier.get());
		}
		this.output.append(PROLOGUE_2);
	}

	@Override
	public void formatStringLiterals(Set<String> stringLiterals) {
		int i = 0;
		for (String lit : stringLiterals) {
			this.output.append("    private static final String STRING_LITERAL_");
			this.output.append(i);
			this.output.append(" = \"");
			this.output.append(lit);
			this.output.append("\";\n");
			++i;
		}
		this.output.append("\n");
	}

	@Override
	public void formatState(State state) {
		new MethodUnderTest(this.output, this.initialStateSupplier.get(), state, this.testCounter);
		++this.testCounter;
	}

	@Override
	public void formatEpilogue() {
		this.output.append("}\n");
	}

	@Override
	public String emit() {
		return this.output.toString();
	}

	@Override
	public void cleanup() {
		this.output = new StringBuilder();
		this.testCounter = 0;
	}

	private static final String INDENT_1 = "    ";
	private static final String INDENT_2 = INDENT_1 + INDENT_1;
	private static final String INDENT_3 = INDENT_1 + INDENT_2;
	private static final String INDENT_4 = INDENT_1 + INDENT_3;
	private static final String PROLOGUE_1 =
			"import static sushi.compile.path_condition_distance.DistanceBySimilarityWithPathCondition.distance;\n" +
			"\n" +
			"import static java.lang.Double.*;\n" +
			"import static java.lang.Math.*;\n" +
			"\n" +
			"import sushi.compile.path_condition_distance.*;\n" +
			"import sushi.logging.Level;\n" +
			"import sushi.logging.Logger;\n" +
			"\n" +
			"import java.util.ArrayList;\n" +
			"import java.util.HashMap;\n" +
			"import java.util.List;\n" +
			"\n" +
			"public class EvoSuiteWrapper";
	private static final String PROLOGUE_2 = " {\n" +
			INDENT_1 + "private static final double SMALL_DISTANCE = 1;\n" +
			INDENT_1 + "private static final double BIG_DISTANCE = 1E300;\n" +
			"\n";

	private static class MethodUnderTest {
		private final StringBuilder s;
		private final HashMap<Symbolic, String> symbolsToVariables = new HashMap<>();
		private final ArrayList<String> evoSuiteInputVariables = new ArrayList<>();
		private boolean panic = false;

		MethodUnderTest(StringBuilder s, State initialState, State finalState, int testCounter) {
			this.s = s;
			makeVariables(finalState);
			appendMethodDeclaration(initialState, finalState, testCounter);
			appendPathCondition(finalState, testCounter);
			appendIfStatement(initialState, finalState, testCounter);
			appendMethodEnd(finalState, testCounter);
		}

		private void appendMethodDeclaration(State initialState, State finalState, int testCounter) {
			if (this.panic) {
				return;
			}

			final List<Symbolic> inputs;
			try {
				inputs = initialState.getStack().get(0).localVariables().values().stream()
						.filter((v) -> v.getValue() instanceof Symbolic)
						.map((v) -> (Symbolic) v.getValue())
						.collect(Collectors.toList());
			} catch (IndexOutOfBoundsException e) {
				throw new UnexpectedInternalException(e);
			}

			this.s.append(INDENT_1);
			this.s.append("public double test");
			this.s.append(testCounter);
			this.s.append("(");
			boolean firstDone = false;
			for (Symbolic symbol : inputs) {
				makeVariableFor(symbol);
				final String varName = getVariableFor(symbol);
				this.evoSuiteInputVariables.add(varName);
				if (firstDone) {
					this.s.append(", ");
				} else {
					firstDone = true;
				}
				final String type;
				if (symbol instanceof ReferenceSymbolic) {
					type = javaClass(((ReferenceSymbolic) symbol).getStaticType(), true);
				} else {
					type = javaPrimitiveType(((PrimitiveSymbolic) symbol).getType());
				}
				this.s.append(type);
				this.s.append(' ');
				this.s.append(varName);
			}
			this.s.append(") throws Exception {\n");
			this.s.append(INDENT_2);
			this.s.append("//generated for state ");
			this.s.append(finalState.getIdentifier());
			this.s.append('[');
			this.s.append(finalState.getSequenceNumber());
			this.s.append("]\n");
		}

		private void appendPathCondition(State finalState, int testCounter) {
			if (this.panic) {
				return;
			}
			this.s.append(INDENT_2);
			this.s.append("final ArrayList<ClauseSimilarityHandler> pathConditionHandler = new ArrayList<>();\n");
			this.s.append(INDENT_2);
			this.s.append("ValueCalculator valueCalculator;\n");
			final Collection<Clause> pathCondition = finalState.getPathCondition();
			for (Iterator<Clause> iterator = pathCondition.iterator(); iterator.hasNext(); ) {
				final Clause clause = iterator.next();
				this.s.append(INDENT_2);
				this.s.append("// "); //comment
				this.s.append(clause.toString());
				this.s.append("\n");
				if (clause instanceof ClauseAssumeExpands) {
					final ClauseAssumeExpands clauseExpands = (ClauseAssumeExpands) clause;
					final Symbolic symbol = clauseExpands.getReference();
					final long heapPosition = clauseExpands.getHeapPosition();
					setWithNewObject(finalState, symbol, heapPosition);
				} else if (clause instanceof ClauseAssumeNull) {
					final ClauseAssumeNull clauseNull = (ClauseAssumeNull) clause;
					final ReferenceSymbolic symbol = clauseNull.getReference();
					setWithNull(symbol);
				} else if (clause instanceof ClauseAssumeAliases) {
					final ClauseAssumeAliases clauseAliases = (ClauseAssumeAliases) clause;
					final Symbolic symbol = clauseAliases.getReference();
					final long heapPosition = clauseAliases.getHeapPosition();
					setWithAlias(finalState, symbol, heapPosition);
				} else if (clause instanceof ClauseAssume) {
					final ClauseAssume clauseAssume = (ClauseAssume) clause;
					final Primitive assumption = clauseAssume.getCondition();
					setNumericAssumption(assumption);
				} else {
					this.s.append(INDENT_2);
					this.s.append(';');
					this.s.append('\n');
				}
			}
			this.s.append("\n");
		}

		private void appendIfStatement(State initialState, State finalState, int testCounter) {
			if (this.panic) {
				return;
			}
			this.s.append(INDENT_2);
			this.s.append("final HashMap<String, Object> candidateObjects = new HashMap<>();\n");
			for (String inputVariable : this.evoSuiteInputVariables) {
				this.s.append(INDENT_2);
				this.s.append("candidateObjects.put(\"");
				this.s.append(generateOriginFromVarName(inputVariable));
				this.s.append("\", ");
				this.s.append(inputVariable);
				this.s.append(");\n");
			}
			this.s.append('\n');
			this.s.append(INDENT_2);
			this.s.append("double d = distance(pathConditionHandler, candidateObjects);\n");
			this.s.append(INDENT_2);
			this.s.append("if (d == 0.0d)\n");
			this.s.append(INDENT_3);
			this.s.append("System.out.println(\"test");
			this.s.append(testCounter);
			this.s.append(" 0 distance\");\n");
			this.s.append(INDENT_2);
			this.s.append("return d;\n");
		}

		private void appendMethodEnd(State finalState, int testCounter) {
			if (this.panic) {
				this.s.delete(0, s.length());
				this.s.append(INDENT_1);
				this.s.append("//Unable to generate test case ");
				this.s.append(testCounter);
				this.s.append(" for state ");
				this.s.append(finalState.getIdentifier());
				this.s.append('[');
				this.s.append(finalState.getSequenceNumber());
				this.s.append("]\n");
			} else {
				this.s.append(INDENT_1);
				this.s.append("}\n");
			}
		}

		private void makeVariables(State finalState) {
			final Collection<Clause> pathCondition = finalState.getPathCondition();
			for (Clause clause : pathCondition) {
				if (clause instanceof ClauseAssumeReferenceSymbolic) {
					final ClauseAssumeReferenceSymbolic clauseRef = (ClauseAssumeReferenceSymbolic) clause;
					final ReferenceSymbolic s = clauseRef.getReference();
					makeVariableFor(s);
				} else if (clause instanceof ClauseAssume) {
					final ClauseAssume clausePrim = (ClauseAssume) clause;
					final List<PrimitiveSymbolic> symbols = symbolsIn(clausePrim.getCondition());
					for (PrimitiveSymbolic s : symbols) {
						makeVariableFor(s);
					}
				} //else do nothing
			}
		}

		private void setWithNewObject(State finalState, Symbolic symbol, long heapPosition) {
			final String expansionClass = javaClass(getTypeOfObjectInHeap(finalState, heapPosition), false);
			this.s.append(INDENT_2);
			this.s.append("pathConditionHandler.add(new SimilarityWithRefToFreshObject(\"");
			this.s.append(symbol.getOrigin());
			this.s.append("\", Class.forName(\"");
			this.s.append(expansionClass); //TODO arrays
			this.s.append("\")));\n");
		}

		private void setWithNull(ReferenceSymbolic symbol) {
			this.s.append(INDENT_2);
			this.s.append("pathConditionHandler.add(new SimilarityWithRefToNull(\"");
			this.s.append(symbol.getOrigin());
			this.s.append("\"));\n");
		}

		private void setWithAlias(State finalState, Symbolic symbol, long heapPosition) {
			final String target = getOriginOfObjectInHeap(finalState, heapPosition);
			this.s.append(INDENT_2);
			this.s.append("pathConditionHandler.add(new SimilarityWithRefToAlias(\"");
			this.s.append(symbol.getOrigin());
			this.s.append("\", \"");
			this.s.append(target);
			this.s.append("\"));\n");
		}

		private String javaPrimitiveType(char type) {
			if (type == Type.BOOLEAN) {
				return "boolean";
			} else if (type == Type.BYTE) {
				return "byte";
			} else if (type == Type.CHAR) {
				return "char";
			} else if (type == Type.DOUBLE) {
				return "double";
			} else if (type == Type.FLOAT) {
				return "float";
			} else if (type == Type.INT) {
				return "int";
			} else if (type == Type.LONG) {
				return "long";
			} else if (type == Type.SHORT) {
				return "short";
			} else {
				return null;
			}
		}

		private String javaClass(String type, boolean forDeclaration) {
			if (type == null) {
				return null;
			}
			final String a = type.replace('/', '.');
			final String s = (forDeclaration ? a.replace('$', '.') : a);

			if (forDeclaration) {
				final char[] tmp = s.toCharArray();
				int arrayNestingLevel = 0;
				boolean hasReference = false;
				int start = 0;
				for (int i = 0; i < tmp.length ; ++i) {
					if (tmp[i] == '[') {
						++arrayNestingLevel;
					} else if (tmp[i] == 'L') {
						hasReference = true;
					} else {
						start = i;
						break;
					}
				}
				final String t = hasReference ? s.substring(start, tmp.length - 1) : javaPrimitiveType(s.charAt(start));
				final StringBuilder retVal = new StringBuilder(t);
				for (int k = 1; k <= arrayNestingLevel; ++k) {
					retVal.append("[]");
				}
				return retVal.toString();
			} else {
				return (isReference(s) ? className(s) : s);
			}
		}

		private String generateVarNameFromOrigin(String name) {
			return name.replace("{ROOT}:", "__ROOT_");
		}

		private String generateOriginFromVarName(String name) {
			return name.replace("__ROOT_", "{ROOT}:");
		}

		private void makeVariableFor(Symbolic symbol) {
			final String origin = symbol.getOrigin().toString();
			if (!this.symbolsToVariables.containsKey(symbol)) {
				this.symbolsToVariables.put(symbol, generateVarNameFromOrigin(origin));
			}
		}

		private String getVariableFor(Symbolic symbol) {
			return this.symbolsToVariables.get(symbol);
		}

		private static String getTypeOfObjectInHeap(State finalState, long num) {
			final Map<Long, Objekt> heap = finalState.getHeap();
			final Objekt o = heap.get(num);
			return o.getType();
		}

		private String getOriginOfObjectInHeap(State finalState, long heapPos){
			final Collection<Clause> path = finalState.getPathCondition();
			for (Clause clause : path) {
				if (clause instanceof ClauseAssumeExpands) { // == Obj fresh
					final ClauseAssumeExpands clauseExpands = (ClauseAssumeExpands) clause;
					final long heapPosCurrent = clauseExpands.getHeapPosition();
					if (heapPosCurrent == heapPos) {
						return generateOriginFromVarName(getVariableFor(clauseExpands.getReference()));
					}
				}
			}
			return null;
		}

		private void setNumericAssumption(Primitive assumption) {
			final List<PrimitiveSymbolic> symbols = symbolsIn(assumption);
			this.s.append(INDENT_2);
			this.s.append("valueCalculator = new ValueCalculator() {\n");
			this.s.append(INDENT_3);
			this.s.append("@Override public Iterable<String> getVariableOrigins() {\n");
			this.s.append(INDENT_4);
			this.s.append("ArrayList<String> retVal = new ArrayList<>();\n");       
			for (PrimitiveSymbolic symbol: symbols) {
				this.s.append(INDENT_4);
				this.s.append("retVal.add(\"");
				this.s.append(symbol.getOrigin());
				this.s.append("\");\n");
			}
			this.s.append(INDENT_4);
			this.s.append("return retVal;\n");       
			this.s.append(INDENT_3);
			this.s.append("}\n");       
			this.s.append(INDENT_3);
			this.s.append("@Override public double calculate(List<Object> variables) {\n");
			for (int i = 0; i < symbols.size(); ++i) {
				final PrimitiveSymbolic symbol = symbols.get(i); 
				this.s.append(INDENT_4);
				this.s.append("final ");
				this.s.append(javaPrimitiveType(symbol.getType()));
				this.s.append(" ");
				this.s.append(javaVariable(symbol));
				this.s.append(" = (");
				this.s.append(javaPrimitiveType(symbol.getType()));
				this.s.append(") variables.get(");
				this.s.append(i);
				this.s.append(");\n");
			}
			this.s.append(INDENT_4);
			this.s.append("return ");
			this.s.append(javaAssumptionCheck(assumption));
			this.s.append(";\n");
			this.s.append(INDENT_3);
			this.s.append("}\n");
			this.s.append(INDENT_2);
			this.s.append("};\n");
			this.s.append(INDENT_2);
			this.s.append("pathConditionHandler.add(new SimilarityWithNumericExpression(valueCalculator));\n");
		}

		private List<PrimitiveSymbolic> symbolsIn(Primitive e) {
			final ArrayList<PrimitiveSymbolic> symbols = new ArrayList<>();
			final PrimitiveVisitor v = new PrimitiveVisitor() {

				@Override
				public void visitWideningConversion(WideningConversion x) throws Exception {
					x.getArg().accept(this);
				}

				@Override
				public void visitTerm(Term x) throws Exception { }

				@Override
				public void visitSimplex(Simplex x) throws Exception { }

				@Override
				public void visitPrimitiveSymbolic(PrimitiveSymbolic s) {
					if (symbols.contains(s)) {
						return;
					}
					symbols.add(s);
				}

				@Override
				public void visitNarrowingConversion(NarrowingConversion x) throws Exception {
					x.getArg().accept(this);
				}

				@Override
				public void visitFunctionApplication(FunctionApplication x) throws Exception {
					for (Primitive p : x.getArgs()) {
						p.accept(this);
					}
				}

				@Override
				public void visitExpression(Expression e) throws Exception {
					if (e.isUnary()) {
						e.getOperand().accept(this);
					} else {
						e.getFirstOperand().accept(this);
						e.getSecondOperand().accept(this);
					}
				}

				@Override
				public void visitAny(Any x) { }
			};

			try {
				e.accept(v);
			} catch (Exception exc) {
				//this should never happen
				throw new AssertionError(exc);
			}
			return symbols;
		}

		private String javaVariable(PrimitiveSymbolic symbol) {
			return symbol.toString().replaceAll("[\\{\\}]", "");
		}

		private Operator dual(Operator op) {
			switch (op) {
			case AND:
				return Operator.OR;
			case OR:
				return Operator.AND;
			case GT:
				return Operator.LE;
			case GE:
				return Operator.LT;
			case LT:
				return Operator.GE;
			case LE:
				return Operator.GT;
			case EQ:
				return Operator.NE;
			case NE:
				return Operator.EQ;
			default:
				return null;
			}
		}

		private String javaAssumptionCheck(Primitive assumption) {
			//first pass: Eliminate negation
			final ArrayList<Primitive> assumptionWithNoNegation = new ArrayList<Primitive>(); //we use only one element as it were a reference to a String variable            
			final PrimitiveVisitor negationEliminator = new PrimitiveVisitor() {

				@Override
				public void visitAny(Any x) throws Exception {
					assumptionWithNoNegation.add(x);
				}

				@Override
				public void visitExpression(Expression e) throws Exception {
					if (e.getOperator().equals(Operator.NOT)) {
						final Primitive operand = e.getOperand();
						if (operand instanceof Simplex) {
							//true or false
							assumptionWithNoNegation.add(operand.not());
						} else if (operand instanceof Expression) {
							final Expression operandExp = (Expression) operand;
							final Operator operator = operandExp.getOperator();
							if (operator.equals(Operator.NOT)) {
								//double negation
								operandExp.getOperand().accept(this);
							} else if (operator.equals(Operator.AND) || operator.equals(Operator.OR)) {
								operandExp.getFirstOperand().not().accept(this);
								final Primitive first = assumptionWithNoNegation.remove(0);
								operandExp.getSecondOperand().not().accept(this);
								final Primitive second = assumptionWithNoNegation.remove(0);
								assumptionWithNoNegation.add(Expression.makeExpressionBinary(null, first, dual(operator), second));
							} else if (operator.equals(Operator.GT) || operator.equals(Operator.GE) ||
									operator.equals(Operator.LT) || operator.equals(Operator.LE) ||
									operator.equals(Operator.EQ) || operator.equals(Operator.NE)) {
								assumptionWithNoNegation.add(Expression.makeExpressionBinary(null, operandExp.getFirstOperand(), dual(operator), operandExp.getSecondOperand()));
							} else {
								//can't do anything for this expression
								assumptionWithNoNegation.add(e);
							}
						} else {
							//can't do anything for this expression
							assumptionWithNoNegation.add(e);
						}
					} else if (e.isUnary()) {
						//in this case the operator can only be NEG
						assumptionWithNoNegation.add(e);
					} else {
						//binary operator
						final Operator operator = e.getOperator();
						e.getFirstOperand().accept(this);
						final Primitive first = assumptionWithNoNegation.remove(0);
						e.getSecondOperand().accept(this);
						final Primitive second = assumptionWithNoNegation.remove(0);
						assumptionWithNoNegation.add(Expression.makeExpressionBinary(null, first, operator, second));
					}
				}

				@Override
				public void visitFunctionApplication(FunctionApplication x) throws Exception {
					final ArrayList<Primitive> newArgs = new ArrayList<>(); 
					for (Primitive arg : x.getArgs()) {
						arg.accept(this);
						newArgs.add(assumptionWithNoNegation.remove(0));
					}
					assumptionWithNoNegation.add(new FunctionApplication(x.getType(), null, x.getOperator(), newArgs.toArray(new Primitive[0])));
				}

				@Override
				public void visitPrimitiveSymbolic(PrimitiveSymbolic s) throws Exception {
					assumptionWithNoNegation.add(s);
				}

				@Override
				public void visitSimplex(Simplex x) throws Exception {
					assumptionWithNoNegation.add(x);
				}

				@Override
				public void visitTerm(Term x) throws Exception {
					assumptionWithNoNegation.add(x);
				}

				@Override
				public void visitNarrowingConversion(NarrowingConversion x) throws Exception {
					assumptionWithNoNegation.add(x);
				}

				@Override
				public void visitWideningConversion(WideningConversion x) throws Exception {
					assumptionWithNoNegation.add(x);
				}

			};
			try {
				assumption.accept(negationEliminator);
			} catch (Exception exc) {
				//this may happen if Any appears in assumption
				throw new RuntimeException(exc);
			}

			//second pass: translate
			final ArrayList<String> translation = new ArrayList<String>(); //we use only one element as it were a reference to a String variable            
			final PrimitiveVisitor translator = new PrimitiveVisitor() {

				@Override
				public void visitWideningConversion(WideningConversion x) throws Exception {
					x.getArg().accept(this);
					final char argType = x.getArg().getType();
					final char type = x.getType();
					if (argType == Type.BOOLEAN && type == Type.INT) {
						//operand stack widening of booleans
						final String arg = translation.remove(0);
						translation.add("((" + arg + ") == false ? 0 : 1)");
					}
				}

				@Override
				public void visitTerm(Term x) {
					translation.add(x.toString());
				}

				@Override
				public void visitSimplex(Simplex x) {
					translation.add(x.getActualValue().toString());
				}

				@Override
				public void visitPrimitiveSymbolic(PrimitiveSymbolic s) {
					translation.add(javaVariable(s));
				}

				@Override
				public void visitNarrowingConversion(NarrowingConversion x)
				throws Exception {
					x.getArg().accept(this);
					final String arg = translation.remove(0);
					final StringBuilder b = new StringBuilder();
					b.append("(");
					b.append(javaPrimitiveType(x.getType()));
					b.append(") (");
					b.append(arg);
					b.append(")");
					translation.add(b.toString());
				}

				@Override
				public void visitFunctionApplication(FunctionApplication x)
				throws Exception {
					final StringBuilder b = new StringBuilder();
					final String[] sig = x.getOperator().split(":");
					b.append(sig[0].replace('/', '.') + "." + sig[2].replace('/', '.'));
					b.append("(");
					boolean firstDone = false;
					for (Primitive p : x.getArgs()) {
						if (firstDone) {
							b.append(", ");
						} else {
							firstDone = true;
						}
						p.accept(this);
						final String arg = translation.remove(0);
						b.append(arg);
					}
					b.append(")");
					translation.add(b.toString());
				}

				@Override
				public void visitExpression(Expression e) throws Exception {
					final StringBuilder b = new StringBuilder();
					final Operator op = e.getOperator();
					if (e.isUnary()) {
						e.getOperand().accept(this);
						final String arg = translation.remove(0);
						b.append(op == Operator.NEG ? "-" : op.toString());
						b.append("(");
						b.append(arg);
						b.append(")");
					} else { 
						e.getFirstOperand().accept(this);
						final String firstArg = translation.remove(0);
						e.getSecondOperand().accept(this);
						final String secondArg = translation.remove(0);
						if (op.equals(Operator.EQ) ||
								op.equals(Operator.GT) ||
								op.equals(Operator.LT) ||
								op.equals(Operator.GE) ||
								op.equals(Operator.LE)) {
							b.append("(");
							b.append(firstArg);
							b.append(") ");
							b.append(op.toString());
							b.append(" (");
							b.append(secondArg);
							b.append(") ? 0 : isNaN((");
							b.append(firstArg);
							b.append(") - (");
							b.append(secondArg);
							b.append(")) ? BIG_DISTANCE : SMALL_DISTANCE + abs((");
							b.append(firstArg);
							b.append(") - (");
							b.append(secondArg);
							b.append("))");
						} else if (op.equals(Operator.NE)) {
							b.append("(");
							b.append(firstArg);
							b.append(") ");
							b.append(op.toString());
							b.append(" (");
							b.append(secondArg);
							b.append(") ? 0 : isNaN((");
							b.append(firstArg);
							b.append(") - (");
							b.append(secondArg);
							b.append(")) ? BIG_DISTANCE : SMALL_DISTANCE");
						} else {
							b.append("(");
							b.append(firstArg);
							b.append(") ");
							if (op.equals(Operator.AND)) {
								b.append("+");
							} else if (op.equals(Operator.OR)) {
								b.append("*");
							} else {
								b.append(op.toString());
							}
							b.append(" (");
							b.append(secondArg);
							b.append(")");
						}
					}
					translation.add(b.toString());
				}

				@Override
				public void visitAny(Any x) throws Exception {
					throw new Exception();
				}
			};
			try {
				assumptionWithNoNegation.get(0).accept(translator);
			} catch (Exception exc) {
				//this may happen if Any appears in assumption
				throw new RuntimeException(exc);
			}

			return translation.get(0);
		}
	}
}

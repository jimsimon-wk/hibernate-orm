/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.processor.annotation;


import javax.lang.model.element.ExecutableElement;

import static org.hibernate.processor.util.Constants.LIST;
import static org.hibernate.processor.util.Constants.UNI;

public class LifecycleMethod extends AbstractAnnotatedMethod {
	private final String entity;
	private final String methodName;
	private final String parameterName;
	private final String operationName;
	private final boolean addNonnullAnnotation;
	private final ParameterKind parameterKind;
	private final boolean returnArgument;
	private final boolean hasGeneratedId;

	public enum ParameterKind {
		NORMAL,
		ARRAY,
		LIST
	}

	public LifecycleMethod(
			AnnotationMetaEntity annotationMetaEntity,
			ExecutableElement method,
			String entity,
			String methodName,
			String parameterName,
			String sessionName,
			String sessionType,
			String operationName,
			boolean addNonnullAnnotation,
			ParameterKind parameterKind,
			boolean returnArgument,
			boolean hasGeneratedId) {
		super(annotationMetaEntity, method, sessionName, sessionType);
		this.entity = entity;
		this.methodName = methodName;
		this.parameterName = parameterName;
		this.operationName = operationName;
		this.addNonnullAnnotation = addNonnullAnnotation;
		this.parameterKind = parameterKind;
		this.returnArgument = returnArgument;
		this.hasGeneratedId = hasGeneratedId;
	}

	@Override
	public boolean hasTypedAttribute() {
		return true;
	}

	@Override
	public boolean hasStringAttribute() {
		return false;
	}

	@Override
	public String getAttributeDeclarationString() {
		StringBuilder declaration = new StringBuilder();
		preamble(declaration);
		nullCheck(declaration, parameterName);
		if ( !isReactive() ) {
			declaration.append( "\ttry {\n" );
		}
		delegateCall(declaration);
		returnArgument(declaration);
		if ( !isReactive() ) {
			if ( returnArgument ) {
				declaration
						.append( ";\n" );
			}
			declaration.append( "\t}\n" );
		}
		convertExceptions( declaration );
		if ( isReactive() ) {
			declaration
					.append( ";\n" );
		}
		declaration.append("}");
		return declaration.toString();
	}

	private void convertExceptions(StringBuilder declaration) {
		if ( operationName.equals("insert") ) {
			handle( declaration,
					"org.hibernate.exception.ConstraintViolationException",
					"jakarta.data.exceptions.EntityExistsException");
		}
		else {
			handle( declaration,
					"org.hibernate.StaleStateException",
					"jakarta.data.exceptions.OptimisticLockingFailureException");
		}
		handle( declaration,
				"jakarta.persistence.PersistenceException",
				"jakarta.data.exceptions.DataException");
	}

	private void returnArgument(StringBuilder declaration) {
		if ( returnArgument ) {
			if ( isReactive() ) {
				declaration
						.append( "\n\t\t\t" )
						.append(".replaceWith(")
						.append(parameterName)
						.append(")");
			}
			else {
				declaration
						.append("\t\treturn ")
						.append(parameterName);
			}
		}
	}

	private void delegateCall(StringBuilder declaration) {
		if ( isReactive() ) {
			// TODO: handle the case of an iterable parameter
			delegateReactively( declaration );
		}
		else {
			delegateBlockingly( declaration );
		}
	}

	private void delegateBlockingly(StringBuilder declaration) {
		if ( isGeneratedIdUpsert() ) {
			declaration
					.append("\t\tif (")
					.append(sessionName)
					.append(".getIdentifier(")
					.append(parameterName)
					.append(") == null)\n")
					.append("\t\t\t")
					.append(sessionName)
					.append('.')
					.append("insert");
			argument( declaration );
			declaration
					.append(";\n")
					.append("\t\telse\n\t");
		}
		declaration
				.append("\t\t")
				.append(sessionName)
				.append('.')
				.append(operationName);
		argument( declaration );
		declaration
				.append(";\n");
	}

	private void argument(StringBuilder declaration) {
		switch ( parameterKind ) {
			case LIST:
				if ( isReactive() ) {
					declaration
							.append("All")
							.append("(")
							.append(parameterName)
							.append(".toArray()")
							.append( ")" );
				}
				else {
					declaration
							.append("Multiple")
							.append("(")
							.append(parameterName)
							.append(")");
				}
				break;
			case ARRAY:
				if ( isReactive() ) {
					declaration
							.append("All")
							.append("(")
							.append(parameterName)
							.append(")");
				}
				else {
					declaration
							.append("Multiple")
							.append("(")
							.append(annotationMetaEntity.importType(LIST))
							.append(".of(")
							.append(parameterName)
							.append("))");
				}
				break;
			default:
				declaration
						.append('(')
						.append(parameterName)
						.append(')');
		}
	}

	private void delegateReactively(StringBuilder declaration) {
		declaration
				.append("\treturn ");
		if ( isReactiveSessionAccess() ) {
			declaration
					.append(sessionName)
					.append(".chain(")
					.append(localSessionName())
					.append(" -> ");
		}
		if ( isGeneratedIdUpsert() ) {
			declaration
					.append("(")
					.append(localSessionName())
					.append(".getIdentifier(")
					.append(parameterName)
					.append(") == null ? ")
					.append(localSessionName())
					.append('.')
					.append("insert")
					.append('(')
					.append(parameterName)
					.append(')')
					.append(" : ");
		}
		declaration
				.append(localSessionName())
				.append( '.' )
				.append( operationName );
		// note that there is no upsertAll() method
		argument( declaration );
		if ( isGeneratedIdUpsert() ) {
			declaration
					.append(')');
		}
		if ( isReactiveSessionAccess() ) {
			declaration
					.append(')');
		}
	}

	private boolean isGeneratedIdUpsert() {
		return "upsert".equals( operationName ) && hasGeneratedId;
	}

	private void preamble(StringBuilder declaration) {
		declaration
				.append("\n@Override\npublic ")
				.append(returnType())
				.append(' ')
				.append(methodName)
				.append('(');
		notNull(declaration);
		declaration
				.append(annotationMetaEntity.importType(entity))
				.append(' ')
				.append(parameterName)
				.append(')')
				.append(" {\n");
	}

	private String returnType() {
		final String entityType = annotationMetaEntity.importType(entity);
		if ( isReactive() ) {
			return annotationMetaEntity.importType(UNI)
					+ '<' + (returnArgument ? entityType : "Void") + '>';
		}
		else {
			return returnArgument ? entityType : "void";
		}
	}

	private void notNull(StringBuilder declaration) {
		if ( addNonnullAnnotation ) {
			declaration
					.append('@')
					.append(annotationMetaEntity.importType("jakarta.annotation.Nonnull"))
					.append(' ');
		}
	}

	@Override
	public String getAttributeNameDeclarationString() {
		throw new UnsupportedOperationException("operation not supported");
	}

	@Override
	public String getMetaType() {
		throw new UnsupportedOperationException("operation not supported");
	}

	@Override
	public String getPropertyName() {
		return methodName;
	}

	@Override
	public String getTypeDeclaration() {
		return entity;
	}
}

// Copyright (c) 2006 - 2008, Clark & Parsia, LLC. <http://www.clarkparsia.com>
// This source code is available under the terms of the Affero General Public License v3.
//
// Please see LICENSE.txt for full license terms, including the availability of proprietary exceptions.
// Questions, comments, or requests for clarification: licensing@clarkparsia.com

package org.mindswap.pellet.jena3.graph.query;

import static org.mindswap.pellet.utils.iterator.IteratorUtils.flatten;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Function;

import org.mindswap.pellet.KnowledgeBase;
import org.mindswap.pellet.jena3.JenaUtils;
import org.mindswap.pellet.jena3.PelletInfGraph;
import org.mindswap.pellet.jena3.graph.loader.GraphLoader;

import aterm.ATermAppl;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.util.iterator.WrappedIterator;

public abstract class TripleQueryHandler {
	public TripleQueryHandler() {
	}
	
	public abstract boolean contains(KnowledgeBase kb, GraphLoader loader, Node subj, Node pred, Node obj);
	
	public abstract ExtendedIterator<Triple> find(KnowledgeBase kb, PelletInfGraph pellet, Node subj, Node pred, Node obj);
	
	protected ExtendedIterator<Triple> objectFiller(Node s, Node p, Collection<ATermAppl> objects) {
		return objectFiller( s, p, objects.iterator() );
	}

	protected ExtendedIterator<Triple> objectFiller(final Node s, final Node p, final Iterator<ATermAppl> objects) {
		Function<ATermAppl, Triple> map = new Function<ATermAppl, Triple>() {
		    public Triple apply( ATermAppl o ) {
		         return Triple.create( s, p, JenaUtils.makeGraphNode( o ) );
		    }
		};
		
		return WrappedIterator.create( objects ).mapWith( map );	
	}
	
	protected ExtendedIterator<Triple> objectSetFiller(Node s, Node p, Set<Set<ATermAppl>> objectSets) {
		return objectFiller( s, p, flatten( objectSets.iterator() ) );
	}
	
	protected ExtendedIterator<Triple> propertyFiller(Node s, Collection<ATermAppl> properties, Node o) {
		return propertyFiller( s, properties.iterator(), o );
	}
	
	protected ExtendedIterator<Triple> propertyFiller(final Node s, final Iterator<ATermAppl> properties, final Node o) {
		Function<ATermAppl, Triple> map = new Function<ATermAppl, Triple>() {
		    public Triple apply( ATermAppl p ) {
		         return Triple.create( s, JenaUtils.makeGraphNode( p ), o );
		    }
		};
		
		return WrappedIterator.create( properties ).mapWith( map );	
	}
	
	protected ExtendedIterator<Triple> subjectFiller(Collection<ATermAppl> subjects, Node p, Node o) {
		return subjectFiller( subjects.iterator(), p, o );
	}
	
	protected ExtendedIterator<Triple> subjectFiller(final Iterator<ATermAppl> subjects, final Node p, final Node o) {
		Function<ATermAppl, Triple> map = new Function<ATermAppl, Triple>() {
		    public Triple apply( ATermAppl s ) {
		         return Triple.create( JenaUtils.makeGraphNode( s ), p, o );
		    }
		};
		
		return WrappedIterator.create( subjects ).mapWith( map );	
	}

	protected ExtendedIterator<Triple> subjectSetFiller(Set<Set<ATermAppl>> subjectSets, Node p, Node o) {
		return subjectFiller( flatten( subjectSets.iterator() ), p, o );
	}
}

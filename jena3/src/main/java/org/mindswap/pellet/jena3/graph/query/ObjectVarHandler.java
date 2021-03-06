// Copyright (c) 2006 - 2008, Clark & Parsia, LLC. <http://www.clarkparsia.com>
// This source code is available under the terms of the Affero General Public License v3.
//
// Please see LICENSE.txt for full license terms, including the availability of proprietary exceptions.
// Questions, comments, or requests for clarification: licensing@clarkparsia.com

package org.mindswap.pellet.jena3.graph.query;

import java.util.Set;

import org.mindswap.pellet.KnowledgeBase;
import org.mindswap.pellet.jena3.PelletInfGraph;
import org.mindswap.pellet.jena3.graph.loader.GraphLoader;

import aterm.ATermAppl;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.util.iterator.ExtendedIterator;

abstract class ObjectVarHandler extends TripleQueryHandler {
	public abstract Set<ATermAppl> getObjects(KnowledgeBase kb, ATermAppl subj);
	
	@Override
	public final boolean contains(KnowledgeBase kb, GraphLoader loader, Node subj, Node pred, Node obj) {
		return !getObjects( kb, loader.node2term( subj ) ).isEmpty();
	}
	
	@Override
	public final ExtendedIterator<Triple> find(KnowledgeBase kb, PelletInfGraph pellet, Node subj, Node pred, Node obj) {
		return objectFiller( subj, pred, getObjects( kb, pellet.getLoader().node2term( subj ) ) );
	}			
}
// Portions Copyright (c) 2006 - 2008, Clark & Parsia, LLC. <http://www.clarkparsia.com>
// Clark & Parsia, LLC parts of this source code are available under the terms of the Affero General Public License v3.
//
// Please see LICENSE.txt for full license terms, including the availability of proprietary exceptions.
// Questions, comments, or requests for clarification: licensing@clarkparsia.com
//
// ---
// Portions Copyright (c) 2003 Ron Alford, Mike Grove, Bijan Parsia, Evren Sirin
// Alford, Grove, Parsia, Sirin parts of this source code are available under the terms of the MIT License.
//
// The MIT License
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to
// deal in the Software without restriction, including without limitation the
// rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
// sell copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
// FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
// IN THE SOFTWARE.

package org.mindswap.pellet.jena3;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.mindswap.pellet.KnowledgeBase;
import org.mindswap.pellet.PelletOptions;
import org.mindswap.pellet.jena3.ModelExtractor.StatementType;
import org.mindswap.pellet.jena3.graph.converter.AxiomConverter;
import org.mindswap.pellet.jena3.graph.loader.DefaultGraphLoader;
import org.mindswap.pellet.jena3.graph.loader.GraphLoader;
import org.mindswap.pellet.jena3.graph.query.GraphQueryHandler;
import org.mindswap.pellet.utils.ATermUtils;

import aterm.ATerm;
import aterm.ATermAppl;

import com.clarkparsia.pellet.utils.OntBuilder;
import org.apache.jena.graph.Factory;
import org.apache.jena.graph.Graph;
import org.apache.jena.util.iterator.UniqueFilter;
import org.apache.jena.util.iterator.WrappedIterator;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.reasoner.BaseInfGraph;
import org.apache.jena.reasoner.Finder;
import org.apache.jena.reasoner.InfGraph;
import org.apache.jena.reasoner.StandardValidityReport;
import org.apache.jena.reasoner.TriplePattern;
import org.apache.jena.reasoner.ValidityReport;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

/**
 * Implementation of Jena InfGraph interface which is backed by Pellet reasoner.
 * 
 * @author Evren Sirin
 */
public class PelletInfGraph extends BaseInfGraph implements InfGraph {
	public final static Logger		log							= Logger
																	.getLogger( PelletInfGraph.class.getName() );
	
	private static final Triple INCONCISTENCY_TRIPLE = 
		Triple.create( OWL.Thing.asNode(), RDFS.subClassOf.asNode(), OWL.Nothing.asNode() );

	private GraphLoader			loader;
	protected KnowledgeBase		kb;
	private final ModelExtractor 		extractor;
	
	private PelletGraphListener graphListener;
	
	private Graph				deductionsGraph;
	
	private boolean				autoDetectChanges;
	
	private boolean				skipBuiltinPredicates;
	
	public PelletInfGraph(KnowledgeBase kb, PelletReasoner pellet, GraphLoader loader) {
		this( kb, Factory.createDefaultGraph(), pellet, loader );
	}
	
	public PelletInfGraph(Graph graph, PelletReasoner pellet, GraphLoader loader) {
		this( new KnowledgeBase(), graph, pellet, loader );
	}
	
	private PelletInfGraph(KnowledgeBase kb, Graph graph, PelletReasoner pellet, GraphLoader loader) {
		super( graph, pellet );
		
		this.kb = kb;
		this.loader = loader;
		
		extractor = new ModelExtractor( kb );
		extractor.setSelector( StatementType.ALL_PROPERTY_STATEMENTS );

		graphListener = new PelletGraphListener( graph, kb, autoDetectChanges );
		
		loader.setKB( kb );
		
		if (pellet.isFixedSchema()) {
			loader.load(Collections.singleton(getSchemaGraph()));
			loader.setLoadTBox(false);
		}
		
		rebind();
	}
	
	public GraphLoader attachTemporaryGraph(Graph tempGraph) {
		GraphLoader savedLoader = loader;
		
		SimpleUnion unionGraph = (SimpleUnion) savedLoader.getGraph();
		unionGraph.addGraph( tempGraph );
				
		loader = new DefaultGraphLoader();
		loader.setGraph( unionGraph );
		loader.setKB( kb );
		loader.preprocess();
		
		return savedLoader;
	}

	public void detachTemporaryGraph(Graph tempGraph, GraphLoader savedLoader) {
		SimpleUnion unionGraph = (SimpleUnion) loader.getGraph();
		unionGraph.removeGraph( tempGraph );
		loader = savedLoader;
	}
	
	@Override
    public ExtendedIterator<Triple> find(Node subject, Node property, Node object, Graph param) {
		prepare();
		
		GraphLoader savedLoader = attachTemporaryGraph( param );

		ExtendedIterator<Triple> result = graphBaseFind( subject, property, object );

		detachTemporaryGraph( param, savedLoader );

		return result;
	}

	@Override
    public ExtendedIterator<Triple> findWithContinuation(TriplePattern pattern, Finder finder) {
		prepare();

		Node subject = pattern.getSubject();
		Node predicate = pattern.getPredicate();
		Node object = pattern.getObject();

		ExtendedIterator<Triple> i = GraphQueryHandler.findTriple( kb, this, subject, predicate, object );

		ATerm predicateTerm = predicate.isURI() ? ATermUtils.makeTermAppl(predicate.getURI()) : null;
		// look at asserted triples at the end but only for annotation properties, other triples should be inferred
		if( finder != null && (predicateTerm == null || !kb.isObjectProperty(predicateTerm) && !kb.isDatatypeProperty(predicateTerm))) {
			TriplePattern tp = new TriplePattern( subject, predicate, object );
			i = i.andThen( finder.find( tp ) );
		}

		// make sure we don't have duplicates
		return WrappedIterator.create(i).filterKeep(new UniqueFilter<Triple>());
	}

	@Override
    public Graph getSchemaGraph() {
		return ((PelletReasoner) getReasoner()).getSchema();
	}

	@Override
    public boolean isPrepared() {
		return super.isPrepared() && (!autoDetectChanges || !graphListener.isChanged());
	}
	
	private void load() {
		if( log.isLoggable( Level.FINE ) ) {
	        log.fine( "Loading triples" );
        }

		Set<Graph> changedGraphs = graphListener.getChangedGraphs();
		
		if( changedGraphs == null ) {
			reload();		
		}
		else {
			load(changedGraphs);
		}		
	}
	
	/**
	 * Reloads all the triple from the underlying models regardless of updates or current state. KB will be cleared
	 * completely and recreated from scratch.
	 */
	public void reload() {
		if( log.isLoggable( Level.FINE ) ) {
            log.fine( "Clearing the KB and reloading" );
        }
		
		clear();

		Set<Graph> graphs = graphListener.getLeafGraphs();
		
		if (loader.isLoadTBox()) {
			Graph schema = getSchemaGraph();
			if( schema != null ) {
				graphs = new HashSet<Graph>(graphs);
				graphs.add(schema);
			}
		}
		
		load(graphs);
	}
	
	private void load(Iterable<Graph> graphs) {		
		loader.load(graphs);
		
		loader.setGraph( new SimpleUnion( graphListener.getLeafGraphs() ) );
		
		graphListener.reset();

		deductionsGraph = null;
	}
	
	@Override
    public void prepare() {
		prepare( true );
	}
	
	public void prepare(boolean doConsistencyCheck) {
		if( isPrepared() ) {
	        return;
        }

		if( log.isLoggable( Level.FINE ) ) {
	        log.fine( "Preparing PelletInfGraph..." );
        }
		
		load();

		kb.prepare();
	
		if( doConsistencyCheck ) {
	        kb.isConsistent();
        }

		if( log.isLoggable( Level.FINE ) ) {
	        log.fine( "done." );
        }

               super.prepare();
	}

	public boolean isConsistent() {
		prepare();

		return kb.isConsistent();
	}

	public boolean isClassified() {
		return super.isPrepared() && kb.isClassified();
	}

	public boolean isRealized() {
		return super.isPrepared() && kb.isRealized();
	}

	public void classify() {
		prepare();

		kb.classify();
	}

	public void realize() {
		prepare();

		kb.realize();
	}
	
	@Override
    @SuppressWarnings("deprecation")
	public Graph getDeductionsGraph() {
		if( !PelletOptions.RETURN_DEDUCTIONS_GRAPH ) {
	        return null;
        }

		classify();

		if( deductionsGraph == null ) {
			if( log.isLoggable( Level.FINE ) ) {
	            log.fine( "Realizing PelletInfGraph..." );
            }
			kb.realize();

			if( log.isLoggable( Level.FINE ) ) {
	            log.fine( "Extract model..." );
            }

			Model extractedModel = extractor.extractModel();
			deductionsGraph = extractedModel.getGraph();

			if( log.isLoggable( Level.FINE ) ) {
	            log.fine( "done." );
            }
		}

		return deductionsGraph;
	}

	@Override
    protected boolean graphBaseContains(Triple pattern) {
		if( getRawGraph().contains( pattern ) ) {
	        return true;
        }
	
		return containsTriple( pattern );
	}
	
	public boolean entails(Triple pattern) {
		prepare();
		
		if( isSyntaxTriple( pattern ) ) {
	        return true;
        }
		
		if( isBnodeTypeQuery( pattern ) ) {
	        return !containsTriple( Triple.create( pattern.getObject(), RDFS.subClassOf.asNode(), OWL.Nothing.asNode() ) );
        }
        else {
	        return containsTriple( pattern );
        }
	}
	
	public Model explainInconsistency() {
		return explainTriple( INCONCISTENCY_TRIPLE );
	}
	
	public Model explain(Statement stmt) {
		return explainTriple( stmt.asTriple() );		
	}

	public Model explain(Resource s, Property p, RDFNode o) {
		return explainTriple( Triple.create( s.asNode(), p.asNode(), o.asNode() ) );
	}
	
	private Model explainTriple(Triple triple) {
		Graph explanation = explain( triple );
		return explanation == null
			? null
			: ModelFactory.createModelForGraph( explanation );
	}
		
	public Graph explain(Triple pattern) {
		if( !pattern.equals( INCONCISTENCY_TRIPLE ) ) {
			if( !pattern.isConcrete() ) {
				if( log.isLoggable( Level.WARNING ) ) {
					log.warning( "Triple patterns with variables cannot be epxlained: " + pattern );
				}
				return null;
			}
			
			if( isSyntaxTriple( pattern ) ) {
				if( log.isLoggable( Level.WARNING ) ) {
					log.warning( "Syntax triples cannot be explained: " + pattern );
				}
				return null;
			}
		}
		
		prepare();

		Graph explanationGraph = Factory.createDefaultGraph();

		if( log.isLoggable( Level.FINE ) ) {
			log.fine( "Explain " + pattern );
		}
		
		if( checkEntailment( this, pattern, true ) ) {
			Set<ATermAppl> explanation = kb.getExplanationSet();
			
			if( log.isLoggable( Level.FINER ) ) {
				log.finer( "Explanation " + formatAxioms( explanation ) );
			}
			
			Set<ATermAppl> prunedExplanation = pruneExplanation( pattern, explanation );
						
			if( log.isLoggable( Level.FINER ) ) {
				log.finer( "Pruned " + formatAxioms( prunedExplanation ) );
			}
			
			AxiomConverter converter = new AxiomConverter( kb, explanationGraph );
			for( ATermAppl axiom : prunedExplanation ) {
	            converter.convert( axiom );
            }
		}
		
		if( log.isLoggable( Level.FINE ) ) {
			log.fine( "Explanation " + explanationGraph );
		}

		return explanationGraph;
	}	

	private Set<ATermAppl> pruneExplanation(Triple pattern, Set<ATermAppl> explanation) {
		Set<ATermAppl> prunedExplanation = new HashSet<ATermAppl>( explanation );
		
		OntBuilder builder = new OntBuilder( kb );
		KnowledgeBase copyKB;
		PelletInfGraph copyGraph;

		GraphLoader loader = new DefaultGraphLoader();
		for( ATermAppl axiom : explanation ) {
			prunedExplanation.remove( axiom );

			copyKB = builder.build( prunedExplanation );
			copyGraph = new PelletInfGraph( copyKB, (PelletReasoner) getReasoner(), loader );
			
			if( !checkEntailment( copyGraph, pattern, false ) ) {
				prunedExplanation.add( axiom );
			}
			else if( log.isLoggable( Level.FINER ) ) {
				log.finer( "Prune from explanation " + ATermUtils.toString( axiom ) );
			}
		}

		return prunedExplanation;
	}
	
	private static boolean checkEntailment(PelletInfGraph pellet, Triple pattern, boolean withExplanation) {
		boolean doExplanation = pellet.getKB().doExplanation();
		pellet.getKB().setDoExplanation( withExplanation );
		
		boolean entailed = false;
		if( pattern.equals( INCONCISTENCY_TRIPLE ) ) {
			entailed = !pellet.isConsistent();
		}
		else {
			entailed = pellet.containsTriple( pattern );
		}
		
		pellet.getKB().setDoExplanation( doExplanation );
		
		return entailed;
	}
	
	private static String formatAxioms(Set<ATermAppl> axioms) {
		StringBuilder sb = new StringBuilder();				
		sb.append( "[" );
		for( ATermAppl axiom : axioms ) {
			sb.append( ATermUtils.toString( axiom ) );
			sb.append( "," );
		}
		if( axioms.isEmpty() ) {
	        sb.append( ']' );
        }
        else {
	        sb.setCharAt( sb.length() - 1, ']' );
        }			
		return sb.toString();
	}
	
	protected boolean containsTriple(Triple pattern) {
		prepare();

		Node subject = pattern.getSubject();
		Node predicate = pattern.getPredicate();
		Node object = pattern.getObject();

		return GraphQueryHandler.containsTriple( kb, loader, subject, predicate, object );
	}
	
	private boolean isSyntaxTriple(Triple t) {
		BuiltinTerm builtin = BuiltinTerm.find( t.getPredicate() );
		
		if( builtin != null ) {
			if( builtin.isSyntax() ) {
				return true;
			}
			
			if( BuiltinTerm.isExpression( builtin ) 
				&& (t.getSubject().isBlank() || t.getObject().isBlank())) {
				return true;
			}
						
			if( builtin.equals( BuiltinTerm.RDF_type ) ) {
				builtin = BuiltinTerm.find( t.getObject() );
				return builtin != null && builtin.isSyntax();
			}
		}
		
		return false;
	}
	
	private boolean isBnodeTypeQuery(Triple t) {
		return t.getSubject().isBlank() 
			&& t.getPredicate().equals( RDF.type.asNode() )
			&& (BuiltinTerm.find( t.getObject() ) == null 
				|| t.getObject().equals( OWL.Thing.asNode() ) 
				|| t.getObject().equals( OWL.Nothing.asNode() ));		
	}

	/**
	 * Returns the underlying Pellet KnowledgeBase. Before calling this function
	 * make sure the graph {@link #isPrepared()} or use {@link #getPreparedKB()}.
	 */
	public KnowledgeBase getKB() {
		return kb;
	}

	/**
	 * Returns the underlying Pellet KnowledgeBase after calling {@link #prepare()}. 
	 */
	public KnowledgeBase getPreparedKB() {
		prepare();
		
		return getKB();
	}

	/**
	 * <p>
	 * Add one triple to the data graph, mark the graph not-prepared, but don't
	 * run prepare() just yet.
	 * </p>
	 * 
	 * @param t
	 *            A triple to add to the graph
	 */
	@Override
    public void performAdd(Triple t) {
		fdata.getGraph().add( t );
		setPreparedState(false);
	}

	/**
	 * <p>
	 * Delete one triple from the data graph, mark the graph not-prepared, but
	 * don't run prepare() just yet.
	 * </p>
	 * 
	 * @param t
	 *            A triple to remove from the graph
	 */
	@Override
    public void performDelete(Triple t) {
		fdata.getGraph().delete( t );
		setPreparedState(false);
	}


	/**
	 * <p>
	 * Test the consistency of the model. This looks for overall inconsistency,
	 * and for any unsatisfiable classes.
	 * </p>
	 * 
	 * @return a ValidityReport structure
	 */
	@Override
    public ValidityReport validate() {
		checkOpen();
		prepare();
		StandardValidityReport report = new StandardValidityReport();

		kb.setDoExplanation( true );
		boolean consistent = kb.isConsistent();
		kb.setDoExplanation( false );

		if( !consistent ) {
			report.add( true, "KB is inconsistent!", kb.getExplanation() );
		}
		else {
			for( ATermAppl c : kb.getUnsatisfiableClasses() ) {
				String name = JenaUtils.makeGraphNode( c ).toString();
				report.add( false, "Unsatisfiable class", name );
			}
		}

		return report;
	}
	
	public void clear() {
		if (loader.isLoadTBox()) {
			kb.clear();
		}
		else {
			kb.clearABox();
		}
		loader.clear();
	}

	@Override
    public void close() {
		close(true);
	}
	
	public void close(boolean recursive) {
		if (closed)
			return;
		
		if (recursive)
			super.close();
		else
			closed = true;
		
		if( deductionsGraph != null ) {
			deductionsGraph.close();
			deductionsGraph = null;
		}
		clear();
		graphListener.dispose();
		graphListener = null;
		kb = null;
	}

	/**
	 * @return the loader
	 */
	public GraphLoader getLoader() {
		return loader;
	}

	public boolean isAutoDetectChanges() {
		return autoDetectChanges;
	}

	/**
	 * Sets auto detection of changes in the subgraphs associated with this model. If a graph or subgraph associated
	 * with this inference graph is updated out of band there is no way to know for the reasoner that the underlying
	 * data has changed and reasoning results will be stale. When this option is turned Pellet will attach listeners to
	 * all the subgraphs and will be notified of all changes. It will also detect addition and removal of new subgraphs.
	 */
	public void setAutoDetectChanges(boolean autoDetectChanges) {
		this.autoDetectChanges = autoDetectChanges;
		
		graphListener.setEnabled(autoDetectChanges);
	}

	public boolean isSkipBuiltinPredicates() {
		return skipBuiltinPredicates;
	}

	public void setSkipBuiltinPredicates(boolean skipBuiltinPredicates) {
		this.skipBuiltinPredicates = skipBuiltinPredicates;
	}
}

package com.ifactory.press.db.solr.spelling.suggest;

import java.io.File;
import java.io.IOException;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.search.suggest.Lookup;
import org.apache.lucene.search.suggest.analyzing.AnalyzingInfixSuggester;
import org.apache.lucene.store.FSDirectory;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.SolrCore;
import org.apache.solr.schema.FieldType;
import org.apache.solr.spelling.suggest.fst.AnalyzingInfixLookupFactory;

public class SafeInfixLookupFactory extends AnalyzingInfixLookupFactory {

    /** 
     * Default path where the index for the suggester is stored/loaded from
     * */
    private static final String DEFAULT_INDEX_PATH = "analyzingInfixSuggesterIndexDir";
    
    @Override
    public Lookup create(@SuppressWarnings("rawtypes") NamedList params, SolrCore core) {
        // mandatory parameter
        Object fieldTypeName = params.get(QUERY_ANALYZER);
        if (fieldTypeName == null) {
            throw new IllegalArgumentException("Error in configuration: " + QUERY_ANALYZER + " parameter is mandatory");
        }
        FieldType ft = core.getLatestSchema().getFieldTypeByName(fieldTypeName.toString());
        if (ft == null) {
            throw new IllegalArgumentException("Error in configuration: " + fieldTypeName.toString() + " is not defined in the schema");
        }
        Analyzer indexAnalyzer = ft.getAnalyzer();
        Analyzer queryAnalyzer = ft.getQueryAnalyzer();
  
        // optional parameters
  
        String indexPath = params.get(INDEX_PATH) != null
                ? params.get(INDEX_PATH).toString()
                        : DEFAULT_INDEX_PATH;
  
        int minPrefixChars = params.get(MIN_PREFIX_CHARS) != null
                ? Integer.parseInt(params.get(MIN_PREFIX_CHARS).toString())
                        : AnalyzingInfixSuggester.DEFAULT_MIN_PREFIX_CHARS;

         try {
             return new SafeInfixSuggester(core.getSolrConfig().luceneMatchVersion, 
                                           FSDirectory.open(new File(indexPath)), indexAnalyzer,
                                           queryAnalyzer, minPrefixChars);
         } catch (IOException e) {
             throw new SolrException(ErrorCode.SERVER_ERROR, e);
         }
    }

}
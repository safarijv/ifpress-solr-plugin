package com.ifactory.press.db.solr.spelling.suggest;

import java.io.Closeable;
import java.io.IOException;
import java.text.BreakIterator;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.Token;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.spell.HighFrequencyDictionary;
import org.apache.lucene.search.spell.SuggestMode;
import org.apache.lucene.search.suggest.Lookup;
import org.apache.lucene.search.suggest.analyzing.AnalyzingInfixSuggester;
import org.apache.lucene.search.suggest.analyzing.AnalyzingSuggester;
import org.apache.lucene.search.suggest.fst.WFSTCompletionLookup;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.lucene.util.CharsRef;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.SolrInputField;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.CloseHook;
import org.apache.solr.core.SolrCore;
import org.apache.solr.search.DocIterator;
import org.apache.solr.search.DocSet;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.spelling.SpellingOptions;
import org.apache.solr.spelling.SpellingResult;
import org.apache.solr.spelling.suggest.Suggester;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;

/**
 * <h3>A suggester that draws suggestions from terms in multiple fields.</h3>
 * 
 * <p>
 * Contributions from each field are weighted by a per-field <b>weight</b>, and
 * zero-weighted based on a global minimum <b>threshold</b> term frequency, a per-field
 * minimum threshold (<b>minfreq</b>) and a per-field maximum (<b>maxfreq</b>) threshold.
 * All thresholds are compared against (term frequency / document count), an estimate of 
 * the fraction of documents containing the term. Thus setting maximum=0.5 will filter out 
 * terms occurring in as many or more than half the documents.
 * </p>
 * 
 * <p>
 * In the normal mode of operation, the given <b>field</b>'s analyzer is used to tokenize the 
 * stored field values; each resulting token becomes a suggestion.
 * </p>
 * 
 * <p>
 * An alternate mode of operation provides for unanalyzed stored field values to
 * be used as suggestions. This mode is selected by specifying
 * <b>analyzerFieldType</b>=string in the suggester configuration. In this mode, every
 * suggestion is given the field's constant <b>weight</b>: term frequency is not considered
 * as part of the weight, and no filtering is applied based on frequency.
 * </p>
 * 
 * <p>
 * If <b>filterDuplicates</b> is set to true for a field, then each suggestion generated by
 * the field is looked up in the suggester: if it exists already, it is not added again.  This
 * slows down indexing considerably so it should only be turned on when actually needed.
 * </p>
 * 
 * <p>
 * The following sample configuration illustrates a setup where suggestions are
 * drawn from a title field and a full text field, with different weights and
 * thresholds.
 * </p>
 * 
 * <pre>
 * {@code
 *  <!-- Suggester -->
 *   <searchComponent name="suggest-component" class="solr.SpellCheckComponent">
 * 
 *     <!-- Multiple "Spell Checkers" can be declared and used by this
 *          component
 *       -->
 * 
 *     <!-- a spellchecker built from a field of the main index -->
 *     <lst name="spellchecker">
 *       <str name="name">suggest-infix-all</str>
 *       <str name="classname">org.apache.solr.spelling.suggest.MultiSuggester</str>
 *       <str name="lookupImpl">org.apache.solr.spelling.suggest.fst.AnalyzingInfixLookupFactory</str>
 *       <str name="suggestAnalyzerFieldType">text</str>
 *       <int name="maxSuggestionLength">80</int>
 *       <float name="threshold">0.0</float>
 *       <!-- true == performance-killer. MultiSuggester handles incremental updates automatically, so there's no need for this anyway. -->
 *       <str name="buildOnCommit">false</str>
 *       <lst name="fields">
 *         <lst name="field">
 *           <str name="name">fulltext_t</str>
 *           <float name="weight">1.0</float>
 *           <float name="minfreq">0.005</float>
 *           <float name="maxfreq">0.3</float>
 *         </lst>
 *         <lst name="field">
 *           <str name="name">title_ms</str>
 *           <float name="weight">10.0</float>
 *         </lst>
 *         <lst name="field">
 *           <!-- a field whose values are weighted by the value of another field in the same document -->
 *           <str name="name">weighted_field_ms</str>
 *           <str name="weight_field">weight_dv</str>
 *           <float name="weight">10.0</float>
 *         </lst>
 *         <lst name="field">
 *           <str name="name">title_t</str>
 *           <analyzerFieldType>string</analyzerFieldType>
 *           <float name="weight">10.0</float>
 *         </lst>
 *         <lst name="field">
 *           <str name="name">duplicate_title_t</str>
 *           <str name="analyzerFieldType">string</str>
 *           <float name="weight">3.0</float>
 *           <bool name="filterDuplicates">true</bool>
 *        </lst>
 *     </lst>
 * 
 *   </searchComponent>
 * }
 * </pre>
 * 
 * 
 * NOTE: the incremental weighting scheme gives an artifical "advantage" to
 * infrequent terms that happen to be indexed first because their weights are
 * normalized when the number of documents is low. To avoid this, it's
 * recommended to rebuild the index periodically. If the index is large and
 * growing relatively slowly, this effect will be very small, though.
 */
@SuppressWarnings("rawtypes")
public class MultiSuggester extends Suggester {

  // weights are stored internally as longs, but externally as small
  // floating point numbers. The floating point weights are multiplied by
  // this factor to convert them to longs with a sufficient
  // range. WEIGHT_SCALE should be greater than the number of documents
  private static final int WEIGHT_SCALE = 10000000;
  private static final int DEFAULT_MAX_SUGGESTION_LENGTH = 80;
  private static final int BUILD_LOGGING_THRESHOLD = 200000;
  private static final int BUILD_COMMIT_THRESHOLD = 10000;
  private static final String EXCLUDE_FORMAT_KEY = "excludeFormat";
  private static final String MAX_SUGGESTION_LENGTH = "maxSuggestionLength";
  private static final String BUILD_ON_STARTUP = "buildOnStartup";
  protected static final String[] ENGLISH_LANGUAGES = {"en", "en-us", "en-gb"};
  private static final String BASE_PARSE_FILTER_STRING = "-format:collection language:(en en-us en-gb)";

  private static final Logger LOG = LoggerFactory.getLogger(MultiSuggester.class);

  private WeightedField[] fields;
  private List<WeightedField> storedFields;
  private List<WeightedField> termFields;
  private int maxSuggestionLength;
  private List<String> excludeFormats;
  private boolean shouldExcludeFormats;
  private boolean buildOnStartup;
  private Set<String> addedStoredValues;

  // use a synchronized Multimap - there may be one with the same name for each
  // core
  private static final ListMultimap<Object, Object> registry = Multimaps.synchronizedListMultimap(ArrayListMultimap.create());

  @Override
  public String init(NamedList config, SolrCore coreParam) {
    String myname = (String) config.get(DICTIONARY_NAME);
    this.core = coreParam;

    // Get formats that determine if doc should be excluded from suggestion build.
    // Use shouldExcludeFormats for faster future boolean checks.
    this.excludeFormats = config.getAll(EXCLUDE_FORMAT_KEY);

    // Grab Solr's buildOnStartup config, which is a string
    this.buildOnStartup = Boolean.valueOf((String) config.get(BUILD_ON_STARTUP));
    this.shouldExcludeFormats = this.excludeFormats != null && !this.excludeFormats.isEmpty();

    if(this.shouldExcludeFormats) {
      LOG.info(String.format("Excluding docs from suggestions that have one of the formats: %s", excludeFormats.toString()));
    }

    // Workaround for SOLR-6246 (lock exception on core reload): close
    // any suggester registered with the same name.

    if (registry.containsKey(myname)) {
      MultiSuggester suggesterToClose = null;
      for (Object o : registry.get(myname)) {
        MultiSuggester suggester = (MultiSuggester) o;
        if (suggester.core.getName().equals(coreParam.getName())) {
          suggesterToClose = suggester;
          break;
        }
      }
      if (suggesterToClose != null) {
        registry.remove(myname, suggesterToClose);
        try {
          suggesterToClose.close();
        } catch (IOException e) {
          LOG.error("An exception occurred while closing the spellchecker", e);
        }
      }
    }
    super.init(config, coreParam);
    // effectively disable analysis *by the SpellChecker/Suggester component*
    // because this leads
    // to independent suggestions for each token; we want AIS to perform
    // analysis and consider the tokens together
    analyzer = new KeywordAnalyzer();
    initWeights((NamedList) config.get("fields"), coreParam);
    Integer maxLengthConfig = (Integer) config.get(MAX_SUGGESTION_LENGTH);
    maxSuggestionLength = maxLengthConfig != null ? maxLengthConfig : DEFAULT_MAX_SUGGESTION_LENGTH;
    registry.put(myname, this);
    core.addCloseHook(new CloseHandler());
    return myname;
  }

  private void initWeights(NamedList fieldConfigs, SolrCore coreParam) {
    fields = new WeightedField[fieldConfigs.size()];
    storedFields = new ArrayList<>();
    termFields = new ArrayList<>();
    for (int ifield = 0; ifield < fieldConfigs.size(); ifield++) {
      NamedList fieldConfig = (NamedList) fieldConfigs.getVal(ifield);
      String fieldName = (String) fieldConfig.get("name");
      Float weight = (Float) fieldConfig.get("weight");
      if (weight == null) {
        weight = 1.0f;
      }
      Float minFreq = (Float) fieldConfig.get("minfreq");
      if (minFreq == null) {
        minFreq = 0.0f;
      }
      Float maxFreq = (Float) fieldConfig.get("maxfreq");
      if (maxFreq == null) {
        maxFreq = 1.0f;
      }
      Boolean filterDuplicates = fieldConfig.getBooleanArg("filterDuplicates");
      if (filterDuplicates == null) {
        filterDuplicates = false;
      }
      String analyzerFieldTypeName = (String) fieldConfig.get("analyzerFieldType");
      Analyzer fieldAnalyzer;
      WeightedField field;

      boolean useStoredField = analyzerFieldTypeName != null;
      if (useStoredField) {
        // useStoredField - when re-building, we retrieve the stored field value
        if ("string".equals(analyzerFieldTypeName)) {
          fieldAnalyzer = null;
        } else {
          fieldAnalyzer = coreParam.getLatestSchema().getFieldTypeByName(analyzerFieldTypeName).getIndexAnalyzer();
        }
        field = new WeightedField(fieldName, weight, minFreq, maxFreq, fieldAnalyzer, useStoredField, filterDuplicates);
        storedFields.add(field);
      } else {
        // Use the existing term values as analyzed by the field
        fieldAnalyzer = coreParam.getLatestSchema().getFieldType(fieldName).getIndexAnalyzer();
        field = new WeightedField(fieldName, weight, minFreq, maxFreq, fieldAnalyzer, useStoredField, filterDuplicates);
        termFields.add(field);
      }
      fields[ifield] = field;
    }
    Arrays.sort(fields);
  }

  @Override
  public void build(SolrCore coreParam, SolrIndexSearcher searcher) throws IOException {
    LOG.info("build suggestion index: " + name);
    reader = searcher.getIndexReader();
    long startTime = System.currentTimeMillis();

    SafariInfixSuggester ais = (SafariInfixSuggester) lookup;
    ais.clear();

    // index all stored fields with one pass through the index
    buildFromStoredFields(storedFields, searcher);

    // index all the terms-based fields using dictionaries
    for (WeightedField termField : termFields) {
      // TODO: refactor b/c we're not really using the MultiDictionary's multiple dictionary capability any more
      dictionary = new MultiDictionary();
      buildFromTerms(termField);
      ais.add(dictionary);
      ais.refresh();
    }

    long endTime = System.currentTimeMillis();
    long totalBuildTime = (endTime - startTime) / 1000;
    LOG.info(String.format("%s suggestion index built: %d suggestions. Total time: %d seconds.", name, ais.getCount(), totalBuildTime));
  }

  private void buildFromStoredFields(List<WeightedField> flds, SolrIndexSearcher searcher) throws IOException {
    LOG.info(String.format("%s Suggest BUILD for stored fields: %s", name, flds));
    long startTime = System.currentTimeMillis();
    long endTime;
    long elapsedSeconds;
    int lastCommitCount = 0;
    int addCount = 0;
    int excludedFormatDocCount = 0;
    int maxDoc = searcher.maxDoc();
    addedStoredValues = new HashSet<>();

    // Get a DocIterator most appropriate for the specific suggest field to avoid iterating all docs when possible.
    OReillySuggestionDocSetFilter suggestDocSetFilter = new OReillySuggestionDocSetFilter(flds, searcher);
    QueryParser parser = new QueryParser("", analyzer);
    DocSet filteredDocSet = null;
    try {
      filteredDocSet = searcher.getDocSet(parser.parse(BASE_PARSE_FILTER_STRING));
    } catch (ParseException e) {
      LOG.error(String.format("%s Error parsing base field queries for stored fields: %s", name, flds));
      e.printStackTrace();
    }

    DocIterator docIt = null;
    if (suggestDocSetFilter.isFilteredSuggestField()) {
      filteredDocSet = suggestDocSetFilter.getFilteredDocSet();
      flds.get(0).fieldName = suggestDocSetFilter.getSuggestFieldName();
    }

    if (filteredDocSet != null && filteredDocSet.size() > 0) {
      docIt = filteredDocSet.iterator();
      maxDoc = filteredDocSet.size();
    }

    LOG.info(String.format("%s Building suggestions using DocSet size %d", name, maxDoc));
    Set<String> fieldsToLoad = flds.stream().map(fld -> fld.fieldName).collect(Collectors.toSet());
    SuggestDocIterator it = new SuggestDocIterator(docIt, maxDoc);

    while(it.hasNext()) {
      int idoc = it.nextDoc();
      Document doc = reader.document(idoc, fieldsToLoad);
      addCount += addSuggestionValues(doc, flds);
      // Progress logging
      if (idoc % BUILD_LOGGING_THRESHOLD == BUILD_LOGGING_THRESHOLD - 1) {
        endTime = System.currentTimeMillis();
        elapsedSeconds = (endTime - startTime) / 1000;
        LOG.info(String.format("%s BUILD for fields %s - Docs added: %d", name, flds, addCount));
        LOG.info(String.format("Docs scanned: %d / %d. %d%% Completed. Time spent: %d seconds.\n", idoc, maxDoc, Math.round(((double)idoc/maxDoc)*100), elapsedSeconds));
      }
      // Commit if reached commit threshold and have added more docs since last commit
      if (addCount % BUILD_COMMIT_THRESHOLD == BUILD_COMMIT_THRESHOLD - 1 && addCount != lastCommitCount) {
        LOG.info(String.format("%s BUILD COMMIT - Docs added: %s\n", name, addCount));
        lastCommitCount = addCount;
        commit(searcher, false); // No need to filter duplicates on full builds, de-duping using HashSet
      }
    }
    commit(searcher, false); // No need to filter duplicates on full builds, de-duping using HashSet
    endTime = System.currentTimeMillis();
    elapsedSeconds = (endTime-startTime) / 1000;
    LOG.info(String.format("%s BUILD COMPLETED for fields %s. Build time: %d seconds.", name, flds, elapsedSeconds));
    LOG.info(String.format("Total docs added: %d. Total docs excluded due to format: %d.\n\n\n", addCount, excludedFormatDocCount));
  }

    // Iterates through all storedFields in the doc, adding their values if they are not empty and not already added.
    // Returns the number of values added.
    private int addSuggestionValues(Document doc, List<WeightedField> storedFields) throws IOException {
      int valuesAdded = 0;
      for(WeightedField fld : storedFields) {
        String fldName = fld.fieldName;
        String value = doc.get(fldName);
        // Only add if value is not null, empty, or already added to suggestions.
        if (value != null && !value.isEmpty() && addedStoredValues.add(value)) {
          valuesAdded++;
          addRaw(fld, value);
        }
      }
      return valuesAdded;
    }

  private void buildFromTerms(WeightedField fld) throws IOException {
    HighFrequencyDictionary hfd = new HighFrequencyDictionary(reader, fld.fieldName, fld.minFreq);
    int numDocs = reader.getDocCount(fld.fieldName);
    int minFreq = (int) (fld.minFreq * numDocs);
    int maxFreq = (int) (fld.maxFreq * numDocs);
    LOG.info(String.format("build suggestions from terms for: %s (min=%d, max=%d, weight=%d)", fld.fieldName, minFreq, maxFreq, fld.weight));
    ((MultiDictionary) dictionary).addDictionary(hfd, minFreq, maxFreq, fld.weight / (2 + numDocs));
  }

  @Override
  public void reload(SolrCore coreParam, SolrIndexSearcher searcher) throws IOException {
    if (lookup instanceof AnalyzingInfixSuggester) {
      // AnalyzingInfixSuggester maintains its own index and sees updates, so we
      // don't need to
      // build it every time the core starts or is reloaded
      AnalyzingInfixSuggester ais = (AnalyzingInfixSuggester) lookup;
      if (ais.getCount() > 0) {
        LOG.info("load existing suggestion index");
        return;
      }
    }

    // Do not start building if SpellChecker's buildOnStartup is set to false.
    if (!this.buildOnStartup) {
      LOG.info(String.format("%s reload: buildOnStartup is false. Skipping build.", name));
      return;
    }
    build(core, searcher);
  }

  /**
   * Adds the field values from the document to the suggester
   * 
   * suggestions for each field are managed using one of the following weighting
   * and update strategies: - constant weight: all terms occurring in the field
   * are weighted equally - frequency weight: terms have a weight that is the
   * field's weight * the number of occurrences frequency-weighted suggestions
   * can have their frequency calculated by: - the value of docFreq() on a
   * source field - a frequency maintained in a docValues field - the current
   * weight in the suggester index
   * 
   * @param doc
   * @param searcher
   * @throws IOException
   */
  public void add(SolrInputDocument doc, SolrIndexSearcher searcher) throws IOException {
    if (!(lookup instanceof SafariInfixSuggester)) {
      return;
    }

    SolrInputField format = doc.getField("format");
    SolrInputField language = doc.getField("language");

    if (format != null && this.excludeFormats.contains(format.getValue().toString())) {
      LOG.info(String.format("Skipping ADD suggestion for doc with a format of: %s", format.getValue().toString()));
      return;
    }

    if (language != null && !Arrays.asList(ENGLISH_LANGUAGES).contains(language.getValue().toString())) {
      LOG.info(String.format("Skipping ADD suggestion for doc with a language of: %s", language.getValue().toString()));
      return;
    }

    for (WeightedField fld : fields) {
      if (!doc.containsKey(fld.fieldName)) {
        continue;
      }
      fld.pendingDocCount++;
      for (Object value : doc.getFieldValues(fld.fieldName)) {
        String strValue = value.toString();
        if (fld.fieldAnalyzer == null) {
          addRaw(fld, strValue);
        } else {
          addTokenized(fld, strValue);
        }
      }
    }
  }

  /**
   * Add the value to the suggester, so it will be available as a suggestion.
   * 
   * @param fld
   *          The WeightedField that value came from
   * @param value
   *          the value to add
   * @throws IOException
   */
  private void addRaw(WeightedField fld, String value) throws IOException {
    if (value.length() > maxSuggestionLength) {
      // break the value into segments if it's too long
      BreakIterator scanner = BreakIterator.getWordInstance();
      scanner.setText(value);
      int offset = 0;
      while (offset < value.length() - maxSuggestionLength) {
        int next = scanner.following(offset + maxSuggestionLength - 1);
        incPending(fld, value.substring(offset, next));
        offset = next;
      }
      // just drop any trailing goo
    } else {
      // add the value unchanged
      incPending(fld, value);
    }
    // LOG.debug ("add raw " + value);
  }

  private void addTokenized(WeightedField fld, String value) throws IOException {
    TokenStream tokens = fld.fieldAnalyzer.tokenStream(fld.fieldName, value);
    tokens.reset();
    CharTermAttribute termAtt = tokens.addAttribute(CharTermAttribute.class);
    Set<String> once = new HashSet<String>();
    try {
      while (tokens.incrementToken()) {
        String token = termAtt.toString();
        token = MultiDictionary.stripAfflatus(token);
        if (once.add(token)) {
          // only add each token once per field value to keep frequencies in line with
          // HighFrequencyDictionary, which counts using TermsEnum.docFreq()
          incPending(fld, token);
          // LOG.debug("add token " + token);
        }
      }
      tokens.end();
    } finally {
      tokens.close();
    }
  }

  private void incPending(WeightedField fld, String suggestion) {
    ConcurrentHashMap<String, Integer> pending = fld.pending;
    if (pending.containsKey(suggestion)) {
      pending.put(suggestion, pending.get(suggestion) + 1);
    } else {
      pending.put(suggestion, 1);
    }
  }

  public void commit(SolrIndexSearcher searcher, boolean filterDuplicates) throws IOException {
    if (!(lookup instanceof SafariInfixSuggester)) {
      return;
    }
    boolean updated = false;
    SafariInfixSuggester ais = (SafariInfixSuggester) lookup;
    for (WeightedField fld : fields) {
      // get the number of documents having this field
      long docCount = searcher.getIndexReader().getDocCount(fld.fieldName) + fld.pendingDocCount;
      fld.pendingDocCount = 0;
      // swap in a new pending map so we can accept new suggestions while we
      // commit
      ConcurrentHashMap<String, Integer> batch = fld.pending;
      fld.pending = new ConcurrentHashMap<String, Integer>(batch.size());
      BytesRef bytes = new BytesRef(maxSuggestionLength);
      BytesRefBuilder bytesRefBuilder = new BytesRefBuilder();  // From Lucene docs: BytesRef should not be used as a buffer, use BytesRefBuilder instead
      bytesRefBuilder.append(bytes);
      Term t = new Term(fld.fieldName, bytesRefBuilder);
      long minCount = (long) (fld.minFreq * docCount);
      long maxCount = (long) (docCount <= 1 ? Long.MAX_VALUE : (fld.maxFreq * docCount + 1));
      updated = updated || !batch.isEmpty();
      for (Map.Entry<String, Integer> e : batch.entrySet()) {
        String term = e.getKey();
        // check for duplicates
        if (filterDuplicates && ais.lookup(term, 1, true, false).size() > 0) {
          // LOG.debug("skipping duplicate " + term);
          continue;
        }
        // TODO: incorporate external metric (eg popularity) into weight
        long weight;
        if (fld.fieldAnalyzer == null) {
          weight = fld.weight;
        } else {
          long count = searcher.getIndexReader().docFreq(t);
          if (count < 0) {
            // FIXME: is this even possible?
            count = e.getValue();
          } else {
            count += e.getValue();
          }
          if (count < minCount || count > maxCount) {
            weight = 0;
          } else {
            weight = (fld.weight * count) / docCount;
          }
        }
        bytesRefBuilder.copyChars(term);
        bytes = bytesRefBuilder.get();
        ais.update(bytes, weight);
      }
    }
    // refresh after each field so the counts will accumulate across fields?
    if (updated) {
      ais.refresh();
    }
  }

  public void close() throws IOException {
    if (lookup != null && lookup instanceof Closeable) {
      ((Closeable) lookup).close();
      lookup = null;
    }
  }
  
  /**
   * Note: this class has a natural ordering that is inconsistent with equals.
   */
  class WeightedField implements Comparable<WeightedField> {
    final static int MAX_TERM_LENGTH = 128;
    final long weight;
    final float minFreq;
    final float maxFreq;
    final Analyzer fieldAnalyzer;
    final boolean useStoredField;
    private ConcurrentHashMap<String, Integer> pending;
    private int pendingDocCount;
    final boolean filterDuplicates;
    String fieldName;

    WeightedField(String name, float weight, float minFreq, float maxFreq, Analyzer analyzer, boolean useStoredField, Boolean filterDuplicates) {
      this.fieldName = name;
      this.weight = (long) (weight * WEIGHT_SCALE);
      this.minFreq = minFreq;
      this.maxFreq = maxFreq;
      this.fieldAnalyzer = analyzer;
      this.useStoredField = useStoredField;
      this.filterDuplicates = filterDuplicates;
      pending = new ConcurrentHashMap<String, Integer>();
      pendingDocCount = 0;
    }

    @Override
      public String toString() {
      return fieldName + '^' + weight;
    }

    @Override
      public int compareTo(WeightedField fld) {
      // sort from highest to lowest
      return (int) (fld.weight - weight);
    }

  }

  class CloseHandler extends CloseHook {

    @Override
    public void postClose(SolrCore c) {
      try {
        close();
      } catch (IOException e) {
        LOG.error("An error occurred while closing: " + e.getMessage(), e);
      }
    }

    @Override
    public void preClose(SolrCore c) {
    }

  }

  /*
    A copy of the getSuggestions() method from Solr's Suggest class (org.apache.solr.spelling.suggest).
    The only modification is the last 'for' loop, where the LookupResult's highlightedKey is used if it exists.
    This overrides Suggest's use of only the non-highlighted key always being used, which does not respect the
    highlights from the AnalyzingInfixSuggester class (or a relative class) as of Solr 4.10.3.

    https://intranet.oreilly.com/jira/browse/SPIDR-1126
   */
  private SpellingResult getSuggestionsWithHighlights(SpellingOptions options) throws IOException {
    SpellingResult res = new SpellingResult();
    if (lookup == null) {
      return res;
    }

    CharsRef scratch = new CharsRef();
    for (Token t : options.tokens) {
      scratch.chars = t.buffer();
      scratch.offset = 0;
      scratch.length = t.length();
      boolean onlyMorePopular = (options.suggestMode == SuggestMode.SUGGEST_MORE_POPULAR) &&
              !(lookup instanceof WFSTCompletionLookup) &&
              !(lookup instanceof AnalyzingSuggester);
      List<Lookup.LookupResult> suggestions = lookup.lookup(scratch, onlyMorePopular, options.count);

      if (suggestions == null) {
        continue;
      }

      if (options.suggestMode != SuggestMode.SUGGEST_MORE_POPULAR) {
        Collections.sort(suggestions);
      }

      // Suggestions should use LookupResult's highlightedKey if not null, otherwise default to using LookupResult's key.
      String lookupKey;
      for (Lookup.LookupResult lr : suggestions) {
        lookupKey = lr.highlightKey != null ? lr.highlightKey.toString() : lr.key.toString();
        res.add(t, lookupKey, (int)lr.value);
      }
    }
    return res;
  }

  @Override
  public SpellingResult getSuggestions(SpellingOptions options) throws IOException {
    SpellingResult result = getSuggestionsWithHighlights(options);
    if (options.extendedResults) {
      for (Map.Entry<?, LinkedHashMap<String, Integer>> suggestion : result.getSuggestions().entrySet()) {
        Object token = suggestion.getKey();
        int freq = 0;
        for (Map.Entry<String, Integer> e : suggestion.getValue().entrySet()) {
          if (e.getKey().equals(token.toString())) {
            freq = e.getValue();
            break;
          }
        }
        result.addFrequency((Token) token, freq);
      }
    }
    return result;
  }

}

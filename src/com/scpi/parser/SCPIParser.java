/*
 * Copyright [2014] [Nicholas Folse]
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.scpi.parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The <code>SCPIParser</code> is a general purpose command parser for
 * SCPI-style commands.
 *
 * Refer to
 * <a href="http://en.wikipedia.org/wiki/Standard_Commands_for_Programmable_Instruments">Standard
 * Commands for Programmable Instruments</a> on Wikipedia for more information
 * about SCPI.
 *
 * <h2>Usage</h2>
 *
 * <p>
 * To the {@link SCPIParser} class can be used in two ways. First, you can use
 * the class as-is by calling {@link addHandler} to add handlers to
 * {@link SCPIParser} objects.</p>
 *
 * <p>
 * The preferred usage is to extend the {@link SCPIParser} class and register
 * handlers in the constructor of the extending class. New handlers are
 * registered by calling the {@link #addHandler addHandler} method and
 * specifying the full, unabbreviated SCPI command path and an
 * {@link SCPICommandHandler}. Command handlers must implement the
 * {@link SCPICommandHandler} functional interface. For example,</p>
 *
 * <h3>Example Usage</h3>
 * <pre>
 * {@code class SimpleSCPIParser extends SCPIParser {
 *    public SimpleSCPIParser() {
 *      addHandler("*IDN?", this::IDN);
 *    }
 *
 *    String IDN(String[] args) {
 *          return "Simple SCPI Parser";
 *    }
 * }
 *
 * SimpleSCPIParser myParser = new SimpleSCPIParser();
 * for (String result : myParser.accept("*IDN?")) {
 *   System.out.println(result);
 * }}
 * </pre>
 *
 * <p>
 * The parser will correctly interpret chained commands of the form
 * <code>*IDN?;MEAS:VOLT:DC?;AC?</code>. In this case, three commands would be
 * processed, where the final command <code>MEAS:VOLT:AC?</code> would be
 * correctly interpreted.</p>
 *
 * <h2>Performance Considerations</h2>
 * <p>
 * By default, the SCPIParser caches
 * {@link #getCacheSizeLimit getCacheSizeLimit()} number of queries, greatly
 * speeding execution of <em>repeated</em> queries (default is 20). This
 * optimizes performance when the parser accepts many <em>identical</em>
 * queries. (Only the parsed version of queries is cached. Results are computed
 * for each call to {@link #accept accept(String query)}.)</p>
 *
 * <p>
 * Commands containing argument values are not cached by default. (For example,
 * queries that send data to the parser.) This prevents caching queries that are
 * not expected to be repeated often. However, if only a small number of queries
 * containing identical argument values can be expected, then caching can be
 * enabled by calling
 * {@link #setCacheQueriesWithArguments setCacheQueriesWithArguments(true)}.</p>
 *
 */
public class SCPIParser {

    private final ConcurrentHashMap<SCPIPath, SCPICommandHandler> handlers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> shortToLongCMD = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<SCPICommandCaller>> acceptCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> acceptCacheKeyFrequency = new ConcurrentHashMap<>();
    private static final Pattern tokenPatterns;
    private static final Pattern upperMatch;
    private static final SCPICommandHandler nullCMDHandler;
    private static int MAX_CACHE_SIZE = 20;
    private static boolean CACHE_QUERIES_WITH_ARGUMENTS = false;

    static {
        tokenPatterns = buildLexer();
        upperMatch = Pattern.compile("[A-Z_*?]+");
        nullCMDHandler = (String[] args) -> null;
    }

    public SCPIParser() {
    }

    /**
     * Adds a <code>SCPICommandHandler</code> for a specified SCPI path.
     *
     * @param path an absolute SCPI path
     * @param handler the method to associate with the path
     */
    public void addHandler(String path, SCPICommandHandler handler) {
        SCPIPath scpipath = new SCPIPath(path);
        Iterator<String> elements = scpipath.iterator();

        while (elements.hasNext()) {
            String element = elements.next();
            Matcher matcher = upperMatch.matcher(element);
            if (matcher.find()) {
                shortToLongCMD.put(matcher.group(), element);
            }
        }
        handlers.put(scpipath, handler);
    }

    /**
     * Accepts query input and returns the results of query processing.
     *
     * Each element in the query is returned as a string, or null if no result
     * was returned by the handler.
     *
     * @param query a string containing input to the parser
     * @return returns an array containing the result of each command contained
     * in the query (may contain null)
     * @throws com.scpi.parser.SCPIParser.SCPIMissingHandlerException this
     * exception may be thrown if the query refers to an unmapped function or
     * contains an error. The caller should handle this exception.
     */
    public String[] accept(String query) throws SCPIMissingHandlerException {
        List<SCPICommandCaller> commands;
        if (MAX_CACHE_SIZE > 0 && acceptCache.containsKey(query)) {
            commands = acceptCache.get(query);
            acceptCacheKeyFrequency.computeIfPresent(query, (k, v) -> {
                v.increment();
                return v;
            });
        } else {
            List<SCPIToken> tokens = lex(query);
            commands = parse(tokens);
            boolean commandsContainsArgument = false;
            if (!CACHE_QUERIES_WITH_ARGUMENTS) {
                for (SCPIToken token : tokens) {
                    if (token.tokenType == SCPITokenType.ARGUMENT) {
                        commandsContainsArgument = true;
                        break;
                    }
                }
            }
            if (MAX_CACHE_SIZE > 0 && !commandsContainsArgument) {
                synchronized (this) {
                    if (acceptCache.size() > MAX_CACHE_SIZE - 1) {
                        String lowestFreqCmd = null;
                        Integer lowestFrequency = Integer.MAX_VALUE;
                        for (String key : acceptCacheKeyFrequency.keySet()) {
                            Integer count = acceptCacheKeyFrequency.get(key).intValue();
                            if (count < lowestFrequency) {
                                lowestFrequency = count;
                                lowestFreqCmd = key;
                            }
                        }
                        acceptCacheKeyFrequency.remove(lowestFreqCmd);
                        acceptCache.remove(lowestFreqCmd);
                    }
                    acceptCache.put(query, commands);
                    acceptCacheKeyFrequency.computeIfAbsent(query, k -> {
                        LongAdder longAdder = new LongAdder();
                        longAdder.increment();
                        return longAdder;
                    });
                }
            }
        }
        String[] results = new String[commands.size()];
        int index = 0;
        for (SCPICommandCaller cmd : commands) {
            results[index++] = cmd.execute();
        }
        return results;
    }

    /* (non-Javadoc)
     * This command should not be used in production code.
     * It returns a reference to the internal key cache frequency map and
     * may be used for testing and optimization purposes.
     * 
     * It is <b>not safe</b> to modify the contents of this map.
     * @return a reference to the internal cache frequency map.
     */
    private ConcurrentHashMap<String, LongAdder> getCacheFrequency() {
        return acceptCacheKeyFrequency;
    }

    /**
     * If set to true, then queries containing argument values will be cached.
     * This can negatively impact performance if many unique queries are sent to
     * the parser.
     *
     * @param newValue desired caching state for queries that contain arguments.
     */
    public void setCacheQueriesWithArguments(boolean newValue) {
        CACHE_QUERIES_WITH_ARGUMENTS = newValue;
    }

    /**
     *
     * @return the current state of the caching queries containing arguments.
     */
    public boolean isCacheQueriesWithArguments() {
        return CACHE_QUERIES_WITH_ARGUMENTS;
    }

    /**
     * Sets the desired cache size limit (number of unique queries). Setting the
     * size to 0 will effectively disable caching. Negative values are
     * interpreted as 0.
     *
     * @param newSize the desired cache size
     */
    public void setCacheSizeLimit(int newSize) {
        MAX_CACHE_SIZE = (newSize >= 0) ? newSize : 0;
    }

    /**
     *
     * @return current cache size limit
     */
    public int getCacheSizeLimit() {
        return MAX_CACHE_SIZE;
    }

    private List<SCPICommandCaller> parse(List<SCPIToken> tokens) throws SCPIMissingHandlerException {
        final List<SCPICommandCaller> commands = new ArrayList<>();
        final SCPIPath activePath = new SCPIPath();
        final ArrayList<String> arguments = new ArrayList<>();
        boolean inCommand = false;
        for (SCPIToken token : tokens) {
            switch (token.tokenType) {
                case COMMAND:
                    // normalize all commands to long-version
                    String longCmd = shortToLongCMD.get(token.data);
                    activePath.append((null == longCmd) ? token.data : longCmd);
                    inCommand = true;
                    break;
                case ARGUMENT:
                case QUOTEDSTRING:
                    arguments.add(token.data);
                    break;
                case COLON:
                    if (!inCommand) {
                        activePath.clear();
                    }
                    break;
                case SEMICOLON:
                    // try to handle the current path
                    SCPICommandHandler activeHandler = handlers.get(activePath);
                    if (null != activeHandler) {
                        commands.add(new SCPICommandCaller(activeHandler, arguments.toArray(new String[arguments.size()])));
                    } else {
                        commands.add(new SCPICommandCaller(nullCMDHandler, new String[]{}));
                        throw new SCPIMissingHandlerException(activePath.toString());
                    }
                    arguments.clear();
                    inCommand = false;
                    activePath.strip();
                    break;
                case NEWLINE:
                case WHITESPACE:
                default:
                    break;
            }
        }
        return commands;
    }

    private static class SCPICommandCaller {

        final SCPICommandHandler handler;
        final String[] args;

        public SCPICommandCaller(SCPICommandHandler handler, String[] args) {
            this.handler = handler;
            this.args = args;
        }

        public String execute() {
            return handler.handle(args);
        }
    }

    private static class SCPIPath {

        private final ArrayList<String> path = new ArrayList<>();
        private int hashCode = 5;

        SCPIPath(String input) {
            path.addAll(Arrays.asList(input.split("\\s*:\\s*")));
            updateHash();
        }

        SCPIPath() {
            updateHash();
        }

        public Iterator<String> iterator() {
            return path.iterator();
        }

        public void clear() {
            this.path.clear();
            updateHash();
        }

        public int length() {
            return path.size();
        }

        @Override
        public String toString() {
            return String.join(":", path);
        }

        public SCPIPath concat(SCPIPath otherPath) {
            path.addAll(otherPath.path);
            updateHash();
            return this;
        }

        public SCPIPath copy() {
            SCPIPath copy = new SCPIPath();
            copy.path.addAll(this.path);
            copy.updateHash();
            return copy;
        }

        public void strip() {
            if (path.size() > 0) {
                path.remove(path.size() - 1);
                updateHash();
            }
        }

        public SCPIPath stripCopy() {
            SCPIPath stripped = new SCPIPath();
            for (int i = 0; i < path.size() - 2; i++) {
                stripped.path.add(this.path.get(i));
            }
            stripped.updateHash();
            return stripped;
        }

        private void append(String element) {
            path.add(element);
            hashCode = 53 * hashCode + element.hashCode();
        }

        private void updateHash() {
            int hash = 5;
            for (String element : path) {
                hash = 53 * hash + element.hashCode();
            }
            hashCode = hash;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final SCPIPath other = (SCPIPath) obj;
            if (this.hashCode != other.hashCode) {
                return false;
            }
            if (this.path.size() != other.path.size()) {
                return false;
            }
            for (int i = 0; i < this.path.size(); i++) {
                if (!this.path.get(i).equals(other.path.get(i))) {

                    return false;
                }
            }
            return true;
        }

    }

    private List<SCPIToken> lex(String input) {
        ArrayList<SCPIToken> tokens = new ArrayList<>();

        // see optimization note for "tokenTypes" in SCPITokenType enum
        final SCPITokenType[] tokenTypes = SCPITokenType.tokenTypes;

        Matcher matcher = tokenPatterns.matcher(input);
        SCPITokenType prevTokenType = SCPITokenType.WHITESPACE;
        while (matcher.find()) {
            for (SCPITokenType tokenType : tokenTypes) {
                String group = matcher.group(tokenType.name());
                if (group != null) {
                    switch (tokenType) {
                        case QUOTEDSTRING:
                            group = group.substring(1, group.length() - 1);
                        case COMMAND:
                        // fall through
                        case ARGUMENT:
                            SCPITokenType typeToAdd = tokenType;
                            if (prevTokenType == SCPITokenType.COMMAND) {
                                typeToAdd = SCPITokenType.ARGUMENT;
                            }
                            tokens.add(new SCPIToken(typeToAdd, group));
                            prevTokenType = SCPITokenType.COMMAND;
                            break;
                        case COLON:
                        // fall through
                        case SEMICOLON:
                            if (tokenType != prevTokenType) {
                                tokens.add(new SCPIToken(tokenType, null));
                                prevTokenType = tokenType;
                            }
                            break;
                        default:
                            break;
                    }
                    break;
                }
            }
        }
        if (prevTokenType != SCPITokenType.SEMICOLON) {
            tokens.add(new SCPIToken(SCPITokenType.SEMICOLON, null));
        }
        return tokens;
    }

    private static enum SCPITokenType {

        COLON(":"),
        SEMICOLON(";"),
        QUOTEDSTRING("\"[^\"]*?\""),
        COMMAND("[a-zA-z*_?]+"),
        ARGUMENT("[a-zA-z0-9\\.]+"),
        WHITESPACE("[ \t]+"),
        NEWLINE("[\r\n]+");
        private final String pattern;

        private SCPITokenType(String pattern) {
            this.pattern = pattern;
        }

        // tokenTypes is an optimization for .values()
        // that prevents the array copy associated with .values()
        // SCPITokenType is private, and the only usage of tokenTypes
        // is in the lexer.  Users of this variable must not modify the contents
        // of tokenTypes!
        private static final SCPITokenType[] tokenTypes;

        static {
            tokenTypes = SCPITokenType.values();
        }
    }

    private static class SCPIToken {

        public SCPITokenType tokenType;
        public String data;

        SCPIToken(SCPITokenType tokenType, String data) {
            this.tokenType = tokenType;
            this.data = data;
        }
    }

    private static Pattern buildLexer() {
        final StringBuilder tokenPatternsBuffer = new StringBuilder();
        for (SCPITokenType tokenType : SCPITokenType.values()) {
            tokenPatternsBuffer.append(String.format("|(?<%s>%s)", tokenType.name(), tokenType.pattern));
        }
        return Pattern.compile(tokenPatternsBuffer.substring(1));
    }

    /**
     * Interface to define a handler for a SCPI command. Implementations of this
     * interface are passed to the {@link #addHandler addHandler} method. Refer to the
     * {@code SCPIParser} class documentation for an example.
     */
    @FunctionalInterface
    public interface SCPICommandHandler {

        public String handle(String[] args);
    }

    /**
     * Base class for SCPI-related exceptions
     */
    public class SCPIException extends Exception {

        public SCPIException(String value) {
            super(value);
        }

    }

    /**
     * An exception that may be raised while parsing a malformed-query, or query
     * that refers to an undefined command.
     */
    public class SCPIMissingHandlerException extends SCPIException {

        private SCPIMissingHandlerException(String value) {
            super(value);
        }

    }

}

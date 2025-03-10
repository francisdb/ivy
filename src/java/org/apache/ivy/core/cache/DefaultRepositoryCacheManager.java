/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.apache.ivy.core.cache;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.ivy.Ivy;
import org.apache.ivy.core.IvyPatternHelper;
import org.apache.ivy.core.module.descriptor.Artifact;
import org.apache.ivy.core.module.descriptor.DefaultArtifact;
import org.apache.ivy.core.module.descriptor.DependencyDescriptor;
import org.apache.ivy.core.module.descriptor.MDArtifact;
import org.apache.ivy.core.module.descriptor.ModuleDescriptor;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.module.id.ModuleRules;
import org.apache.ivy.core.report.ArtifactDownloadReport;
import org.apache.ivy.core.report.DownloadStatus;
import org.apache.ivy.core.report.MetadataArtifactDownloadReport;
import org.apache.ivy.core.resolve.ResolvedModuleRevision;
import org.apache.ivy.core.settings.IvySettings;
import org.apache.ivy.plugins.IvySettingsAware;
import org.apache.ivy.plugins.lock.LockStrategy;
import org.apache.ivy.plugins.matcher.ExactPatternMatcher;
import org.apache.ivy.plugins.matcher.MapMatcher;
import org.apache.ivy.plugins.matcher.Matcher;
import org.apache.ivy.plugins.matcher.NoMatcher;
import org.apache.ivy.plugins.matcher.PatternMatcher;
import org.apache.ivy.plugins.namespace.NameSpaceHelper;
import org.apache.ivy.plugins.parser.ModuleDescriptorParser;
import org.apache.ivy.plugins.parser.ModuleDescriptorParserRegistry;
import org.apache.ivy.plugins.parser.ParserSettings;
import org.apache.ivy.plugins.parser.xml.XmlModuleDescriptorParser;
import org.apache.ivy.plugins.repository.ArtifactResourceResolver;
import org.apache.ivy.plugins.repository.Repository;
import org.apache.ivy.plugins.repository.Resource;
import org.apache.ivy.plugins.repository.ResourceDownloader;
import org.apache.ivy.plugins.repository.ResourceHelper;
import org.apache.ivy.plugins.resolver.AbstractResolver;
import org.apache.ivy.plugins.resolver.DependencyResolver;
import org.apache.ivy.plugins.resolver.util.ResolvedResource;
import org.apache.ivy.util.Checks;
import org.apache.ivy.util.FileUtil;
import org.apache.ivy.util.HexEncoder;
import org.apache.ivy.util.Message;
import org.apache.ivy.util.PropertiesFile;

public class DefaultRepositoryCacheManager implements RepositoryCacheManager, IvySettingsAware {
    private static final String DEFAULT_ARTIFACT_PATTERN =
        "[organisation]/[module](/[branch])/[type]s/[artifact]-[revision](-[classifier])(.[ext])";

    private static final String DEFAULT_DATA_FILE_PATTERN = 
        "[organisation]/[module](/[branch])/ivydata-[revision].properties";

    private static final String DEFAULT_IVY_PATTERN = 
        "[organisation]/[module](/[branch])/ivy-[revision].xml";
    
    private static final int DEFAULT_MEMORY_CACHE_SIZE = 150;
    
    private static MessageDigest SHA_DIGEST;
    static {
        try {
            SHA_DIGEST = MessageDigest.getInstance("SHA1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("The SHA1 algorithm is not available in your classpath", e);
        }
    }
    
    private IvySettings settings;
    
    private File basedir;

    private LockStrategy lockStrategy;

    private String name;

    private String ivyPattern; 

    private String dataFilePattern = DEFAULT_DATA_FILE_PATTERN; 
    
    private String artifactPattern;

    private String lockStrategyName; 

    private String changingPattern;

    private String changingMatcherName = PatternMatcher.EXACT_OR_REGEXP;

    private Boolean checkmodified;

    private Boolean useOrigin;
    
    private ModuleRules/*<Long>*/ ttlRules = new ModuleRules();

    private Long defaultTTL = null;

    private ModuleDescriptorMemoryCache memoryModuleDescrCache;

    public DefaultRepositoryCacheManager() {
    }

    public DefaultRepositoryCacheManager(String name, IvySettings settings, File basedir) {
        setName(name);
        setSettings(settings);
        setBasedir(basedir);
    }

    public IvySettings getSettings() {
        return settings;
    }

    public void setSettings(IvySettings settings) {
        this.settings = settings;
    }

    public File getIvyFileInCache(ModuleRevisionId mrid) {
        String file = IvyPatternHelper.substitute(getIvyPattern(), DefaultArtifact
                .newIvyArtifact(mrid, null));
        return new File(getRepositoryCacheRoot(), file);
    }

    public String getIvyPattern() {
        if (ivyPattern == null) {
            if (settings != null) {
                ivyPattern = settings.getDefaultCacheIvyPattern();
            }
            if (ivyPattern == null) {
                ivyPattern = DEFAULT_IVY_PATTERN;
            }
        }
        return ivyPattern;
    }
    
    public String getArtifactPattern() {
        if (artifactPattern == null) {
            if (settings != null) {
                artifactPattern = settings.getDefaultCacheArtifactPattern();
            }
            if (artifactPattern == null) {
                artifactPattern = DEFAULT_ARTIFACT_PATTERN;
            }
        }
        return artifactPattern;
    }

    public void setArtifactPattern(String artifactPattern) {
        CacheUtil.checkCachePattern(artifactPattern);
        this.artifactPattern = artifactPattern;
    }

    public File getBasedir() {
        if (basedir == null) {
            basedir = settings.getDefaultRepositoryCacheBasedir();
        }
        return basedir;
    }

    public void setBasedir(File cache) {
        this.basedir = cache;
    }
    
    public long getDefaultTTL() {
        if (defaultTTL == null) {
            defaultTTL = new Long(parseDuration(settings.getVariable("ivy.cache.ttl.default")));
        }
        return defaultTTL.longValue();
    }
    
    public void setDefaultTTL(long defaultTTL) {
        this.defaultTTL = new Long(defaultTTL);
    }
    
    public void setDefaultTTL(String defaultTTL) {
        this.defaultTTL = new Long(parseDuration(defaultTTL));
    }

    public String getDataFilePattern() {
        return dataFilePattern;
    }

    public void setDataFilePattern(String dataFilePattern) {
        CacheUtil.checkCachePattern(dataFilePattern);
        this.dataFilePattern = dataFilePattern;
    }

    public void setIvyPattern(String ivyPattern) {
        CacheUtil.checkCachePattern(ivyPattern);
        this.ivyPattern = ivyPattern;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getChangingMatcherName() {
        return changingMatcherName;
    }

    public void setChangingMatcher(String changingMatcherName) {
        this.changingMatcherName = changingMatcherName;
    }

    public String getChangingPattern() {
        return changingPattern;
    }

    public void setChangingPattern(String changingPattern) {
        this.changingPattern = changingPattern;
    }

    public void addTTL(Map attributes, PatternMatcher matcher, long duration) {
        ttlRules.defineRule(new MapMatcher(attributes, matcher), new Long(duration));
    }
    
    public void addConfiguredTtl(Map/*<String,String>*/ attributes) {
        String duration = (String) attributes.remove("duration");
        if (duration == null) {
            throw new IllegalArgumentException("'duration' attribute is mandatory for ttl");
        }
        String matcher = (String) attributes.remove("matcher");
        addTTL(
            attributes, 
            matcher == null ? ExactPatternMatcher.INSTANCE : settings.getMatcher(matcher), 
                    parseDuration(duration));
    }

    public void setMemorySize(int size) {
        memoryModuleDescrCache = new ModuleDescriptorMemoryCache(size);
    }
    
    public ModuleDescriptorMemoryCache getMemoryCache() {
        if (memoryModuleDescrCache == null) {
            memoryModuleDescrCache = new ModuleDescriptorMemoryCache(DEFAULT_MEMORY_CACHE_SIZE);
        }
        return memoryModuleDescrCache;
    }
    
    
    private static final Pattern DURATION_PATTERN 
        = Pattern.compile("(?:(\\d+)d)? ?(?:(\\d+)h)? ?(?:(\\d+)m)? ?(?:(\\d+)s)? ?(?:(\\d+)ms)?");

    private static final int MILLIS_IN_SECONDS = 1000; 
    private static final int MILLIS_IN_MINUTES = 60 * MILLIS_IN_SECONDS;
    private static final int MILLIS_IN_HOUR = 60 * MILLIS_IN_MINUTES;
    private static final int MILLIS_IN_DAY = 24 * MILLIS_IN_HOUR;

    private long parseDuration(String duration) {
        if (duration == null) {
            return 0;
        }
        if ("eternal".equals(duration)) {
            return Long.MAX_VALUE;
        }
        java.util.regex.Matcher m = DURATION_PATTERN.matcher(duration);
        if (m.matches()) {
            //CheckStyle:MagicNumber| OFF
            int days = getGroupIntValue(m, 1);
            int hours = getGroupIntValue(m, 2);
            int minutes = getGroupIntValue(m, 3);
            int seconds = getGroupIntValue(m, 4);
            int millis = getGroupIntValue(m, 5);
            //CheckStyle:MagicNumber| ON
            
            return days * MILLIS_IN_DAY 
            + hours * MILLIS_IN_HOUR
            + minutes * MILLIS_IN_MINUTES
            + seconds * MILLIS_IN_SECONDS
            + millis;
        } else {
            throw new IllegalArgumentException("invalid duration '" 
                + duration + "': it must match " + DURATION_PATTERN.pattern()
                + " or 'eternal'");
        }
    }

    private int getGroupIntValue(java.util.regex.Matcher m, int groupNumber) {
        String g = m.group(groupNumber);
        return g == null || g.length() == 0 ? 0 : Integer.parseInt(g);
    }

    /**
     * True if this cache should check lastmodified date to know if ivy files are up to date.
     * 
     * @return
     */
    public boolean isCheckmodified() {
        if (checkmodified == null) {
            if (getSettings() != null) {
                String check = getSettings().getVariable("ivy.resolver.default.check.modified");
                return check != null ? Boolean.valueOf(check).booleanValue() : false;
            } else {
                return false;
            }
        } else {
            return checkmodified.booleanValue();
        }
    }

    public void setCheckmodified(boolean check) {
        checkmodified = Boolean.valueOf(check);
    }
    
    /**
     * True if this cache should use artifacts original location when possible, false if they should
     * be copied to cache.
     */
    public boolean isUseOrigin() {
        if (useOrigin == null) {
            if (getSettings() != null) {
                return getSettings().isDefaultUseOrigin();
            } else {
                return false;
            }
        } else {
            return useOrigin.booleanValue();
        }
    }

    public void setUseOrigin(boolean b) {
        useOrigin = Boolean.valueOf(b);
    }
    
    /**
     * Returns a File object pointing to where the artifact can be found on the local file system.
     * This is usually in the cache, but it can be directly in the repository if it is local and if
     * the resolve has been done with useOrigin = true
     */
    public File getArchiveFileInCache(Artifact artifact) {
        ArtifactOrigin origin = getSavedArtifactOrigin(artifact);
        return getArchiveFileInCache(artifact, origin);
    }

    /**
     * Returns a File object pointing to where the artifact can be found on the local file system.
     * This is usually in the cache, but it can be directly in the repository if it is local and if
     * the resolve has been done with useOrigin = true
     */
    public File getArchiveFileInCache(Artifact artifact, ArtifactOrigin origin) {
        File archive = new File(getRepositoryCacheRoot(), getArchivePathInCache(artifact, origin));
        if (!archive.exists() 
                && !ArtifactOrigin.isUnknown(origin) && origin.isLocal()) {
            File original = Checks.checkAbsolute(
                origin.getLocation(), artifact + " origin location");
            if (original.exists()) {
                return original;
            }
        }
        return archive;
    }

    /**
     * Returns a File object pointing to where the artifact can be found on the local file system,
     * using or not the original location depending on the availability of origin information
     * provided as parameter and the setting of useOrigin. If useOrigin is false, this method will
     * always return the file in the cache.
     */
    private File getArchiveFileInCache(
            Artifact artifact, ArtifactOrigin origin, boolean useOrigin) {
        if (useOrigin && !ArtifactOrigin.isUnknown(origin) && origin.isLocal()) {
            return Checks.checkAbsolute(origin.getLocation(), artifact + " origin location");
        } else {
            return new File(getRepositoryCacheRoot(), getArchivePathInCache(artifact, origin));
        }
    }

    public String getArchivePathInCache(Artifact artifact) {
        return IvyPatternHelper.substitute(getArtifactPattern(), artifact);
    }

    public String getArchivePathInCache(Artifact artifact, ArtifactOrigin origin) {
        if (isOriginalMetadataArtifact(artifact)) {
            return IvyPatternHelper.substitute(getIvyPattern() + ".original", artifact, origin);
        } else {
            return IvyPatternHelper.substitute(getArtifactPattern(), artifact, origin);
        }
    }

    /**
     * Saves the information of which resolver was used to resolve a md, so that this info can be
     * retrieve later (even after a jvm restart) by getSavedResolverName(ModuleDescriptor md)
     * 
     * @param md
     *            the module descriptor resolved
     * @param name
     *            resolver name
     */
    private void saveResolver(ModuleDescriptor md, String name) {
        // should always be called with a lock on module metadata artifact
        PropertiesFile cdf = getCachedDataFile(md);
        cdf.setProperty("resolver", name);
        cdf.save();
    }

    /**
     * Saves the information of which resolver was used to resolve a md, so that this info can be
     * retrieve later (even after a jvm restart) by getSavedArtResolverName(ModuleDescriptor md)
     */
    public void saveResolvers(
            ModuleDescriptor md, String metadataResolverName, String artifactResolverName) {
        ModuleRevisionId mrid = md.getResolvedModuleRevisionId();
        if (!lockMetadataArtifact(mrid)) {
            Message.error("impossible to acquire lock for " + mrid);
            return;
        }
        try {
            PropertiesFile cdf = getCachedDataFile(md);
            cdf.setProperty("resolver", metadataResolverName);
            cdf.setProperty("artifact.resolver", artifactResolverName);
            cdf.save();
        } finally {
            unlockMetadataArtifact(mrid);
        }
    }

    private String getSavedResolverName(ModuleDescriptor md) {
        // should always be called with a lock on module metadata artifact
        PropertiesFile cdf = getCachedDataFile(md);
        return cdf.getProperty("resolver");
    }

    private String getSavedArtResolverName(ModuleDescriptor md) {
        // should always be called with a lock on module metadata artifact
        PropertiesFile cdf = getCachedDataFile(md);
        return cdf.getProperty("artifact.resolver");
    }

    void saveArtifactOrigin(Artifact artifact, ArtifactOrigin origin) {
        // should always be called with a lock on module metadata artifact
        PropertiesFile cdf = getCachedDataFile(artifact.getModuleRevisionId());
        cdf.setProperty(getIsLocalKey(artifact), String.valueOf(origin.isLocal()));
        cdf.setProperty(getLocationKey(artifact), origin.getLocation());
        if (origin.getLastChecked() != null) {
            cdf.setProperty(getLastCheckedKey(artifact), origin.getLastChecked().toString());
        }
        cdf.setProperty(getExistsKey(artifact), Boolean.toString(origin.isExists()));
        cdf.save();
    }

    private void removeSavedArtifactOrigin(Artifact artifact) {
        // should always be called with a lock on module metadata artifact
        PropertiesFile cdf = getCachedDataFile(artifact.getModuleRevisionId());
        cdf.remove(getLocationKey(artifact));
        cdf.remove(getIsLocalKey(artifact));
        cdf.remove(getLastCheckedKey(artifact));
        cdf.save();
    }

    public ArtifactOrigin getSavedArtifactOrigin(Artifact artifact) {
        ModuleRevisionId mrid = artifact.getModuleRevisionId();
        if (!lockMetadataArtifact(mrid)) {
            Message.error("impossible to acquire lock for " + mrid);
            return ArtifactOrigin.unkwnown(artifact);
        }
        try {
            PropertiesFile cdf = getCachedDataFile(artifact.getModuleRevisionId());
            String location = cdf.getProperty(getLocationKey(artifact));
            String local = cdf.getProperty(getIsLocalKey(artifact));
            String lastChecked = cdf.getProperty(getLastCheckedKey(artifact));
            String exists = cdf.getProperty(getExistsKey(artifact));

            boolean isLocal = Boolean.valueOf(local).booleanValue();

            if (location == null) {
                // origin has not been specified, return null
                return ArtifactOrigin.unkwnown(artifact);
            }

            ArtifactOrigin origin = new ArtifactOrigin(artifact, isLocal, location);
            if (lastChecked != null) {
                origin.setLastChecked(Long.valueOf(lastChecked));
            }
            if (exists != null) {
                origin.setExist(Boolean.valueOf(exists).booleanValue());
            }

            return origin;
        } finally {
            unlockMetadataArtifact(mrid);
        }
    }

    /**
     * Creates the unique prefix key that will reference the artifact within the properties.
     * 
     * @param artifact
     *            the artifact to create the unique key from. Cannot be null.
     * @return the unique prefix key as a string.
     */
    private String getPrefixKey(Artifact artifact) {
        // use the hashcode as a uuid for the artifact (fingers crossed)
        int hashCode = artifact.getId().hashCode();
        // use just some visual cue
        return "artifact:" + artifact.getName() + "#" + artifact.getType() + "#"
                + artifact.getExt() + "#" + hashCode;
    }

    /**
     * Returns the key used to identify the location of the artifact.
     * 
     * @param artifact
     *            the artifact to generate the key from. Cannot be null.
     * @return the key to be used to reference the artifact location.
     */
    private String getLocationKey(Artifact artifact) {
        String prefix = getPrefixKey(artifact);
        return prefix + ".location";
    }

    /**
     * Returns the key used to identify if the artifact is local.
     * 
     * @param artifact
     *            the artifact to generate the key from. Cannot be null.
     * @return the key to be used to reference the artifact locality.
     */
    private String getIsLocalKey(Artifact artifact) {
        String prefix = getPrefixKey(artifact);
        return prefix + ".is-local";
    }

    /**
     * Returns the key used to identify the last time the artifact was checked to be up to date.
     * 
     * @param artifact
     *            the artifact to generate the key from. Cannot be null.
     * @return the key to be used to reference the artifact's last check date.
     */
    private String getLastCheckedKey(Artifact artifact) {
        String prefix = getPrefixKey(artifact);
        return prefix + ".lastchecked";
    }

    /**
     * Returns the key used to identify the existence of the remote artifact.
     * 
     * @param artifact
     *            the artifact to generate the key from. Cannot be null.
     * @return the key to be used to reference the existence of the artifact.
     */
    private String getExistsKey(Artifact artifact) {
        String prefix = getPrefixKey(artifact);
        return prefix + ".exists";
    }

    private PropertiesFile getCachedDataFile(ModuleDescriptor md) {
        return getCachedDataFile(md.getResolvedModuleRevisionId());
    }

    private PropertiesFile getCachedDataFile(ModuleRevisionId mRevId) {
        return new PropertiesFile(new File(getRepositoryCacheRoot(), 
            IvyPatternHelper.substitute(
                getDataFilePattern(), mRevId)), "ivy cached data file for " + mRevId);
    }

    public ResolvedModuleRevision findModuleInCache(
            DependencyDescriptor dd, ModuleRevisionId requestedRevisionId, 
            CacheMetadataOptions options, String expectedResolver) {
        ModuleRevisionId mrid = requestedRevisionId;
        if (isCheckmodified(dd, requestedRevisionId, options)) {
            Message.verbose("don't use cache for " + mrid + ": checkModified=true");
            return null;
        }
        if (!options.isUseCacheOnly() && isChanging(dd, requestedRevisionId, options)) {
            Message.verbose("don't use cache for " + mrid + ": changing=true");
            return null;
        }
        return doFindModuleInCache(mrid, options, expectedResolver);
    }

    private ResolvedModuleRevision doFindModuleInCache(
            ModuleRevisionId mrid, CacheMetadataOptions options, String expectedResolver) {
        if (!lockMetadataArtifact(mrid)) {
            Message.error("impossible to acquire lock for " + mrid);
            return null;
        }
        
        boolean unlock = true;
        
        try {
            if (settings.getVersionMatcher().isDynamic(mrid)) {
                String resolvedRevision = getResolvedRevision(mrid, options);
                if (resolvedRevision != null) {
                    Message.verbose("found resolved revision in cache: " 
                        + mrid + " => " + resolvedRevision);
                    
                    // we have found another module in the cache, make sure we unlock
                    // the original module
                    unlockMetadataArtifact(mrid);
                    mrid = ModuleRevisionId.newInstance(mrid, resolvedRevision);
                    
                    // don't forget to request a lock on the new module!
                    if (!lockMetadataArtifact(mrid)) {
                        Message.error("impossible to acquire lock for " + mrid);
                        
                        // we couldn't lock the new module, so no need to unlock it
                        unlock = false;
                        return null;
                    }
                } else {
                    return null;
                }
            }

            File ivyFile = getIvyFileInCache(mrid);
            if (ivyFile.exists()) {
                // found in cache !
                try {
                    XmlModuleDescriptorParser parser = XmlModuleDescriptorParser.getInstance();
                    ModuleDescriptor depMD = getMdFromCache(parser, options, ivyFile);
                    String resolverName = getSavedResolverName(depMD);
                    String artResolverName = getSavedArtResolverName(depMD);
                    DependencyResolver resolver = settings.getResolver(resolverName);
                    if (resolver == null) {
                        Message.debug("\tresolver not found: " + resolverName
                            + " => trying to use the one configured for " + mrid);
                        resolver = settings.getResolver(depMD.getResolvedModuleRevisionId());
                        if (resolver != null) {
                            Message.debug("\tconfigured resolver found for "
                                + depMD.getResolvedModuleRevisionId() + ": "
                                + resolver.getName() + ": saving this data");
                            saveResolver(depMD, resolver.getName());
                        }
                    }
                    DependencyResolver artResolver = settings.getResolver(artResolverName);
                    if (artResolver == null) {
                        artResolver = resolver;
                    }
                    if (resolver != null) {
                        Message.debug("\tfound ivy file in cache for " + mrid + " (resolved by "
                            + resolver.getName() + "): " + ivyFile);
                        if (expectedResolver == null 
                                || expectedResolver.equals(resolver.getName())) {
                            MetadataArtifactDownloadReport madr 
                            = new MetadataArtifactDownloadReport(
                                depMD.getMetadataArtifact());
                            madr.setDownloadStatus(DownloadStatus.NO);
                            madr.setSearched(false);
                            madr.setLocalFile(ivyFile);
                            madr.setSize(ivyFile.length());
                            madr.setArtifactOrigin(
                                getSavedArtifactOrigin(depMD.getMetadataArtifact()));
                            return new ResolvedModuleRevision(
                                resolver, artResolver, depMD, madr);
                        } else {
                            Message.debug(
                                "found module in cache but with a different resolver: "
                                + "discarding: " + mrid 
                                + "; expected resolver=" + expectedResolver 
                                + "; resolver=" + resolver.getName());
                        }
                    } else {
                        Message.debug("\tresolver not found: " + resolverName
                            + " => cannot use cached ivy file for " + mrid);
                    }
                } catch (Exception e) {
                    // will try with resolver
                    Message.debug("\tproblem while parsing cached ivy file for: " + mrid + ": "
                        + e.getMessage());
                }
            } else {
                Message.debug("\tno ivy file in cache for " + mrid + ": tried " + ivyFile);
            }
        } finally {
            if (unlock) {
                unlockMetadataArtifact(mrid);
            }
        }
        return null;
    }

    
    private class MyModuleDescriptorProvider implements ModuleDescriptorProvider {
        
        private final ModuleDescriptorParser mdParser;
        private final ParserSettings settings;

        public MyModuleDescriptorProvider(ModuleDescriptorParser mdParser, ParserSettings settings) {
            this.mdParser = mdParser;
            this.settings = settings;
        }
        
        public ModuleDescriptor provideModule(ParserSettings ivySettings, 
                File descriptorURL, boolean validate) throws ParseException, IOException {
            return mdParser.parseDescriptor(settings, descriptorURL.toURI().toURL(), validate);
        }
    }
    
    private ModuleDescriptor getMdFromCache(XmlModuleDescriptorParser mdParser, 
            CacheMetadataOptions options, File ivyFile) 
            throws ParseException, IOException {
        ModuleDescriptorMemoryCache cache = getMemoryCache();
        ModuleDescriptorProvider mdProvider = new MyModuleDescriptorProvider(mdParser, settings); 
        return cache.get(ivyFile, settings, options.isValidate(), mdProvider);
    }

    private ModuleDescriptor getStaledMd(ModuleDescriptorParser mdParser, 
            CacheMetadataOptions options, File ivyFile, ParserSettings parserSettings) 
            throws ParseException, IOException {
        ModuleDescriptorMemoryCache cache = getMemoryCache();
        ModuleDescriptorProvider mdProvider = new MyModuleDescriptorProvider(mdParser, parserSettings); 
        return cache.getStale(ivyFile, settings, options.isValidate(), mdProvider);
    }

    
    private String getResolvedRevision(ModuleRevisionId mrid, CacheMetadataOptions options) {
        if (!lockMetadataArtifact(mrid)) {
            Message.error("impossible to acquire lock for " + mrid);
            return null;
        }
        try {
            String resolvedRevision = null;
            if (options.isForce()) {
                Message.verbose("refresh mode: no check for cached resolved revision for " + mrid);
                return null;
            }
            PropertiesFile cachedResolvedRevision = getCachedDataFile(mrid);
            resolvedRevision = cachedResolvedRevision.getProperty("resolved.revision");
            if (resolvedRevision == null) {
                Message.verbose(getName() + ": no cached resolved revision for " + mrid);
                return null;
            }
            
            String resolvedTime = cachedResolvedRevision.getProperty("resolved.time");
            if (resolvedTime == null) {
                Message.verbose(getName() 
                    + ": inconsistent or old cache: no cached resolved time for " + mrid);
                saveResolvedRevision(mrid, resolvedRevision);
                return resolvedRevision;
            }
            if (options.isCheckTTL()) {
                long expiration = Long.parseLong(resolvedTime) + getTTL(mrid);
                if (expiration > 0 // negative expiration means that Long.MAX_VALUE has been exceeded
                        && System.currentTimeMillis() > expiration) {
                    Message.verbose(getName() + ": cached resolved revision expired for " + mrid);
                    return null;
                }
            }
            return resolvedRevision;
        } finally {
            unlockMetadataArtifact(mrid);
        }
    }

    public void saveResolvedRevision(ModuleRevisionId mrid, String revision) {
        if (!lockMetadataArtifact(mrid)) {
            Message.error("impossible to acquire lock for " + mrid);
            return;
        }
        try {
            PropertiesFile cachedResolvedRevision = getCachedDataFile(mrid);
            cachedResolvedRevision.setProperty(
                "resolved.time", String.valueOf(System.currentTimeMillis()));
            cachedResolvedRevision.setProperty("resolved.revision", revision);
            cachedResolvedRevision.save();
        } finally {
            unlockMetadataArtifact(mrid);
        }
    }

    public long getTTL(ModuleRevisionId mrid) {
        Long ttl = (Long) ttlRules.getRule(mrid);
        return ttl == null ? getDefaultTTL() : ttl.longValue();
    }

    public String toString() {
        return name;
    }

    public File getRepositoryCacheRoot() {
        return getBasedir();
    }

    public LockStrategy getLockStrategy() {
        if (lockStrategy == null) {
            if (lockStrategyName != null) {
                lockStrategy = settings.getLockStrategy(lockStrategyName);
            } else {
                lockStrategy = settings.getDefaultLockStrategy();
            }
        }
        return lockStrategy;
    }
    
    public void setLockStrategy(LockStrategy lockStrategy) {
        this.lockStrategy = lockStrategy;
    }
    
    public void setLockStrategy(String lockStrategyName) {
        this.lockStrategyName = lockStrategyName;
    }
    
    public ArtifactDownloadReport download(
            Artifact artifact, 
            ArtifactResourceResolver resourceResolver, 
            ResourceDownloader resourceDownloader, 
            CacheDownloadOptions options) {
        final ArtifactDownloadReport adr = new ArtifactDownloadReport(artifact);
        boolean useOrigin = isUseOrigin();
        
        // TODO: see if we could lock on the artifact to download only, instead of the module
        // metadata artifact. We'd need to store artifact origin and is local in artifact specific
        // file to do so, or lock the metadata artifact only to update artifact origin, which would
        // mean acquiring nested locks, which can be a dangerous thing
        ModuleRevisionId mrid = artifact.getModuleRevisionId();
        if (!lockMetadataArtifact(mrid)) {
            adr.setDownloadStatus(DownloadStatus.FAILED);
            adr.setDownloadDetails("impossible to get lock for " + mrid);
            return adr;
        }
        try {
            DownloadListener listener = options.getListener();
            if (listener != null) {
                listener.needArtifact(this, artifact);
            }
            ArtifactOrigin origin = getSavedArtifactOrigin(artifact);
            // if we can use origin file, we just ask ivy for the file in cache, and it will
            // return the original one if possible. If we are not in useOrigin mode, we use the
            // getArchivePath method which always return a path in the actual cache
            File archiveFile = getArchiveFileInCache(artifact, origin, useOrigin);

            if (archiveFile.exists() && !options.isForce()) {
                adr.setDownloadStatus(DownloadStatus.NO);
                adr.setSize(archiveFile.length());
                adr.setArtifactOrigin(origin);
                adr.setLocalFile(archiveFile);
            } else {
                long start = System.currentTimeMillis();
                try {
                    ResolvedResource artifactRef = resourceResolver.resolve(artifact);
                    if (artifactRef != null) {
                        origin = new ArtifactOrigin(
                            artifact,
                            artifactRef.getResource().isLocal(),
                            artifactRef.getResource().getName());
                        if (useOrigin && artifactRef.getResource().isLocal()) {
                            saveArtifactOrigin(artifact, origin);
                            archiveFile = getArchiveFileInCache(artifact, origin);
                            adr.setDownloadStatus(DownloadStatus.NO);
                            adr.setSize(archiveFile.length());
                            adr.setArtifactOrigin(origin);
                            adr.setLocalFile(archiveFile);
                        } else {
                            // refresh archive file now that we better now its origin
                            archiveFile = getArchiveFileInCache(artifact, origin, useOrigin);
                            if (ResourceHelper.equals(artifactRef.getResource(), archiveFile)) {
                                throw new IllegalStateException("invalid settings for '"
                                    + resourceResolver
                                    + "': pointing repository to ivy cache is forbidden !");
                            } 
                            if (listener != null) {
                                listener.startArtifactDownload(this, artifactRef, artifact, origin);
                            }

                            resourceDownloader.download(
                                artifact, artifactRef.getResource(), archiveFile);
                            adr.setSize(archiveFile.length());
                            saveArtifactOrigin(artifact, origin);
                            adr.setDownloadTimeMillis(System.currentTimeMillis() - start);
                            adr.setDownloadStatus(DownloadStatus.SUCCESSFUL);
                            adr.setArtifactOrigin(origin);
                            adr.setLocalFile(archiveFile);
                        }
                    } else {
                        adr.setDownloadStatus(DownloadStatus.FAILED);
                        adr.setDownloadDetails(ArtifactDownloadReport.MISSING_ARTIFACT);
                        adr.setDownloadTimeMillis(System.currentTimeMillis() - start);
                    }
                } catch (Exception ex) {
                    adr.setDownloadStatus(DownloadStatus.FAILED);
                    adr.setDownloadDetails(ex.getMessage());
                    adr.setDownloadTimeMillis(System.currentTimeMillis() - start);
                }
            }
            if (listener != null) {
                listener.endArtifactDownload(this, artifact, adr, archiveFile);
            }
            return adr;
        } finally {
            unlockMetadataArtifact(mrid);
        }
    }

    public ArtifactDownloadReport downloadRepositoryResource(final Resource resource, String name,
            String type, String extension, CacheResourceOptions options, Repository repository) {

        String hash = computeResourceNameHash(resource);
        ModuleRevisionId mrid = ModuleRevisionId.newInstance("_repository_metadata_", hash,
            Ivy.getWorkingRevision());
        Artifact artifact = new DefaultArtifact(mrid, null, name, type, extension);
        final ArtifactDownloadReport adr = new ArtifactDownloadReport(artifact);
        boolean useOrigin = isUseOrigin();

        try {
            DownloadListener listener = options.getListener();
            if (listener != null) {
                listener.needArtifact(this, artifact);
            }
            ArtifactOrigin savedOrigin = getSavedArtifactOrigin(artifact);
            File archiveFile = getArchiveFileInCache(artifact, savedOrigin, useOrigin);

            ArtifactOrigin origin = new ArtifactOrigin(artifact, resource.isLocal(), resource.getName());

            if (!options.isForce()
                    // if the local file has been checked to be up to date enough recently, don't download
                    && checkCacheUptodate(archiveFile, resource, savedOrigin, origin, options.getTtl())) {
                if (archiveFile.exists()) {
                    saveArtifactOrigin(artifact, origin);
                    adr.setDownloadStatus(DownloadStatus.NO);
                    adr.setSize(archiveFile.length());
                    adr.setArtifactOrigin(savedOrigin);
                    adr.setLocalFile(archiveFile);
                } else {
                    // we trust the cache to says that the resource doesn't exist
                    adr.setDownloadStatus(DownloadStatus.FAILED);
                    adr.setDownloadDetails("Remote resource is known to not exist");
                }
            } else {
                long start = System.currentTimeMillis();
                origin.setLastChecked(new Long(start));
                try {
                    ResolvedResource artifactRef = new ResolvedResource(resource,
                            Ivy.getWorkingRevision());
                    if (useOrigin && resource.isLocal()) {
                        saveArtifactOrigin(artifact, origin);
                        archiveFile = getArchiveFileInCache(artifact, origin);
                        adr.setDownloadStatus(DownloadStatus.NO);
                        adr.setSize(archiveFile.length());
                        adr.setArtifactOrigin(origin);
                        adr.setLocalFile(archiveFile);
                    } else { 
                        if (listener != null) {
                            listener.startArtifactDownload(this, artifactRef, artifact, origin);
                        }

                        // actual download
                        if (archiveFile.exists()) {
                            archiveFile.delete();
                        }
                        File part = new File(archiveFile.getAbsolutePath() + ".part");
                        repository.get(resource.getName(), part);
                        if (!part.renameTo(archiveFile)) {
                            throw new IOException(
                                    "impossible to move part file to definitive one: " + part
                                            + " -> " + archiveFile);
                        }

                        adr.setSize(archiveFile.length());
                        saveArtifactOrigin(artifact, origin);
                        adr.setDownloadTimeMillis(System.currentTimeMillis() - start);
                        adr.setDownloadStatus(DownloadStatus.SUCCESSFUL);
                        adr.setArtifactOrigin(origin);
                        adr.setLocalFile(archiveFile);
                    }
                } catch (Exception ex) {
                    origin.setExist(false);
                    saveArtifactOrigin(artifact, origin);
                    adr.setDownloadStatus(DownloadStatus.FAILED);
                    adr.setDownloadDetails(ex.getMessage());
                    adr.setDownloadTimeMillis(System.currentTimeMillis() - start);
                }
            }
            if (listener != null) {
                listener.endArtifactDownload(this, artifact, adr, archiveFile);
            }
            return adr;
        } finally {
            unlockMetadataArtifact(mrid);
        }
    }

    /**
     * Compute a SHA1 of the resource name, encoded in base64, so we can use it as a file name.
     * 
     * @param resource
     *            the resource which name will be hashed
     * @return the hash
     */
    private String computeResourceNameHash(Resource resource) {
        byte[] shaDigest;
        try {
            shaDigest = SHA_DIGEST.digest(resource.getName().getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("UTF-8 not supported", e);
        }
        return HexEncoder.encode(shaDigest);
    }

    /**
     * Check that a cached file can be considered up to date and thus not downloaded
     * 
     * @param archiveFile
     *            the file in the cache
     * @param resource
     *            the remote resource to check
     * @param savedOrigin
     *            the saved origin which contains that last checked date
     * @param origin
     *            the origin in which to store the new last checked date
     * @param ttl
     *            the time to live to consider the cache up to date
     * @return <code>true</code> if the cache is considered up to date
     */
    private boolean checkCacheUptodate(File archiveFile, Resource resource,
            ArtifactOrigin savedOrigin, ArtifactOrigin origin, long ttl) {
        long time = System.currentTimeMillis();
        if (savedOrigin.getLastChecked() != null
                && (time - savedOrigin.getLastChecked().longValue()) < ttl) {
            // still in the ttl period, no need to check, trust the cache
            if (!archiveFile.exists()) {
                // but if the local archive doesn't exist, trust the cache only if the cached origin
                // says that the remote resource doesn't exist either
                return !savedOrigin.isExists();
            }
            return true;
        }
        if (!archiveFile.exists()) {
            // the the file doesn't exist in the cache, obviously not up to date
            return false;
        }
        origin.setLastChecked(new Long(time));
        // check if the local resource is up to date regarding the remote one
        return archiveFile.lastModified() >= resource.getLastModified();
    }

    public void originalToCachedModuleDescriptor(
            DependencyResolver resolver, ResolvedResource orginalMetadataRef,
            Artifact requestedMetadataArtifact,
            ResolvedModuleRevision rmr, ModuleDescriptorWriter writer) {
        ModuleDescriptor md = rmr.getDescriptor();
        Artifact originalMetadataArtifact = getOriginalMetadataArtifact(requestedMetadataArtifact);
        File mdFileInCache = getIvyFileInCache(md.getResolvedModuleRevisionId());

        ModuleRevisionId mrid = requestedMetadataArtifact.getModuleRevisionId();
        if (!lockMetadataArtifact(mrid)) {
            Message.warn("impossible to acquire lock for: " + mrid);
            return;
        }
        try {
            File originalFileInCache = getArchiveFileInCache(originalMetadataArtifact);
            writer.write(orginalMetadataRef, md, 
                originalFileInCache, 
                mdFileInCache);

            getMemoryCache().putInCache(mdFileInCache, new ParserSettingsMonitor(settings), true, md);
            saveResolvers(md, resolver.getName(), resolver.getName());
            
            if (!md.isDefault()) {
                rmr.getReport().setOriginalLocalFile(originalFileInCache);
            }
            rmr.getReport().setLocalFile(mdFileInCache);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Message.warn("impossible to put metadata file in cache: " 
                + (orginalMetadataRef == null 
                        ? String.valueOf(md.getResolvedModuleRevisionId()) 
                        : String.valueOf(orginalMetadataRef))
                + ". " + e.getClass().getName() + ": " + e.getMessage());
        } finally {
            unlockMetadataArtifact(mrid);
        }
    }

    public ResolvedModuleRevision cacheModuleDescriptor(
            DependencyResolver resolver, final ResolvedResource mdRef, DependencyDescriptor dd, 
            Artifact moduleArtifact, ResourceDownloader downloader, CacheMetadataOptions options) 
            throws ParseException {
        Date cachedPublicationDate = null;
        ArtifactDownloadReport report;
        ModuleRevisionId mrid = moduleArtifact.getModuleRevisionId();
        if (!lockMetadataArtifact(mrid)) {
            Message.error("impossible to acquire lock for " + mrid);
            return null;
        }

        BackupResourceDownloader backupDownloader = new BackupResourceDownloader(downloader);

        try {
            if (!moduleArtifact.isMetadata()) {
                // the descriptor we are trying to cache is a default one, not much to do
                // just make sure the old artifacts are deleted...
                if (isChanging(dd, mrid, options)) {
                    long repoLastModified = mdRef.getLastModified();
        
                    Artifact transformedArtifact = NameSpaceHelper.transform(
                        moduleArtifact, options.getNamespace().getToSystemTransformer());
                    ArtifactOrigin origin = getSavedArtifactOrigin(transformedArtifact);
                    File artFile = getArchiveFileInCache(transformedArtifact, origin, false);
                    if (artFile.exists() && repoLastModified > artFile.lastModified()) {
                        // artifacts have changed, they should be downloaded again
                        Message.verbose(mrid + " has changed: deleting old artifacts");
                        Message.debug("deleting " + artFile);
                        if (!artFile.delete()) {
                            Message.error("Couldn't delete outdated artifact from cache: " + artFile);
                            return null;
                        }
                        removeSavedArtifactOrigin(transformedArtifact);
                    }
                }
                return null;
            }
            
            // now let's see if we can find it in cache and if it is up to date
            ResolvedModuleRevision rmr = doFindModuleInCache(mrid, options, null);
            if (rmr != null) {
                if (rmr.getDescriptor().isDefault() && rmr.getResolver() != resolver) {
                    Message.verbose("\t" + getName() + ": found revision in cache: " + mrid
                        + " (resolved by " + rmr.getResolver().getName()
                        + "): but it's a default one, maybe we can find a better one");
                } else {
                    if (!isCheckmodified(dd, mrid, options) && !isChanging(dd, mrid, options)) {
                        Message.verbose("\t" + getName() + ": revision in cache: " + mrid);
                        rmr.getReport().setSearched(true);
                        return rmr;
                    }
                    long repLastModified = mdRef.getLastModified();
                    long cacheLastModified = rmr.getDescriptor().getLastModified();
                    if (!rmr.getDescriptor().isDefault() && repLastModified <= cacheLastModified) {
                        Message.verbose("\t" + getName() + ": revision in cache (not updated): "
                            + mrid);
                        rmr.getReport().setSearched(true);
                        return rmr;
                    } else {
                        Message.verbose("\t" + getName() + ": revision in cache is not up to date: "
                            + mrid);
                        if (isChanging(dd, mrid, options)) {
                            // ivy file has been updated, we should see if it has a new publication
                            // date to see if a new download is required (in case the dependency is
                            // a changing one)
                            cachedPublicationDate = 
                                rmr.getDescriptor().getResolvedPublicationDate();
                        }
                    }
                }
            }
        
            Artifact originalMetadataArtifact = getOriginalMetadataArtifact(moduleArtifact);
            // now download module descriptor and parse it
            report = download(
                originalMetadataArtifact, 
                new ArtifactResourceResolver() {
                    public ResolvedResource resolve(Artifact artifact) {
                        return mdRef;
                    }
                }, backupDownloader,
                new CacheDownloadOptions().setListener(options.getListener()).setForce(true));
            Message.verbose("\t" + report); 

            if (report.getDownloadStatus() == DownloadStatus.FAILED) {
                Message.warn("problem while downloading module descriptor: " + mdRef.getResource() 
                    + ": " + report.getDownloadDetails() 
                    + " (" + report.getDownloadTimeMillis() + "ms)");
                return null;
            }

            try {
                ModuleDescriptorParser parser = ModuleDescriptorParserRegistry
                        .getInstance().getParser(mdRef.getResource());
                ParserSettings parserSettings = settings;
                if (resolver instanceof AbstractResolver) {
                    parserSettings = ((AbstractResolver) resolver).getParserSettings();
                }
                ModuleDescriptor md = getStaledMd(parser, options, report.getLocalFile(), parserSettings); 
                if (md == null) {
                    throw new IllegalStateException(
                        "module descriptor parser returned a null module descriptor, " 
                        + "which is not allowed. "
                        + "parser=" + parser 
                        + "; parser class=" + parser.getClass().getName()
                        + "; module descriptor resource=" + mdRef.getResource());
                }
                Message.debug("\t" + getName() + ": parsed downloaded md file for " + mrid 
                    + "; parsed=" + md.getModuleRevisionId());

                // check if we should delete old artifacts
                boolean deleteOldArtifacts = false;
                if (cachedPublicationDate != null
                        && !cachedPublicationDate.equals(md.getResolvedPublicationDate())) {
                    // artifacts have changed, they should be downloaded again
                    Message.verbose(mrid + " has changed: deleting old artifacts");
                    deleteOldArtifacts = true;
                }
                if (deleteOldArtifacts) {
                    String[] confs = md.getConfigurationsNames();
                    for (int i = 0; i < confs.length; i++) {
                        Artifact[] arts = md.getArtifacts(confs[i]);
                        for (int j = 0; j < arts.length; j++) {
                            if (!prepAndDeleteArtifact(arts[j], options, backupDownloader)) return null;
                        }
                    }

                    // sbt change, driven by https://github.com/sbt/sbt/issues/1750
                    //
                    // Typically Ivy behaviour, in the parsing above
                    // (with ModuleDescriptorParser & PomModuleDescriptorBuilder),
                    // involves making remote calls to locate sources and javadoc jars on the remote repository
                    // and storing them in the ModuleDescriptor.
                    //
                    // However given a number of dependencies and resolver this suffers in performance,
                    // particurlarly Ivy's lack of caching for not finding artifacts or IO failures.
                    //
                    // So sbt overrides this behaviour by failing fast (returning null).
                    // See IvySbt.hasImplicitClassifier.
                    //
                    // This however has the side-effect that sources and javadoc jars aren't deleted here
                    // so that they can be re-downloaded.
                    //
                    // So we keep the performance overrides and just delete those jars directly below.

                    if ("true".equals(
                        settings.substitute("${alwaysDeleteSourcesAndJavadocJarsOnChangedModuleDescriptor}")
                    )) {
                        final Artifact sourceArtifact = new MDArtifact(md, mrid.getName(), "src", "jar", null,
                            Collections.singletonMap("m:classifier", "sources"));

                        final Artifact srcArtifact = new MDArtifact(md, mrid.getName(), "src", "jar", null,
                            Collections.singletonMap("m:classifier", "src"));

                        final Artifact javadocArtifact = new MDArtifact(md, mrid.getName(), "doc", "jar", null,
                            Collections.singletonMap("m:classifier", "javadoc"));

                        if (!prepAndDeleteArtifact(sourceArtifact,  options, backupDownloader)) return null;
                        if (!prepAndDeleteArtifact(srcArtifact,     options, backupDownloader)) return null;
                        if (!prepAndDeleteArtifact(javadocArtifact, options, backupDownloader)) return null;
                    }

                    // end of sbt change

                } else if (isChanging(dd, mrid, options)) {
                    Message.verbose(mrid
                        + " is changing, but has not changed: will trust cached artifacts if any");
                }
                
                MetadataArtifactDownloadReport madr 
                    = new MetadataArtifactDownloadReport(md.getMetadataArtifact());
                madr.setSearched(true);
                madr.setDownloadStatus(report.getDownloadStatus());
                madr.setDownloadDetails(report.getDownloadDetails());
                madr.setArtifactOrigin(report.getArtifactOrigin());
                madr.setDownloadTimeMillis(report.getDownloadTimeMillis());
                madr.setOriginalLocalFile(report.getLocalFile());
                madr.setSize(report.getSize());
                
                Artifact transformedMetadataArtifact = NameSpaceHelper.transform(
                    md.getMetadataArtifact(), options.getNamespace().getToSystemTransformer());
                saveArtifactOrigin(transformedMetadataArtifact, report.getArtifactOrigin());
                
                return new ResolvedModuleRevision(resolver, resolver, md, madr);
            } catch (IOException ex) {
                Message.warn("io problem while parsing ivy file: " + mdRef.getResource() + ": "
                    + ex.getMessage());
                return null;
            }
        } finally {
            unlockMetadataArtifact(mrid);
            backupDownloader.cleanUp();
        }
        
    }

    // lock used to lock all metadata related information access
    private boolean lockMetadataArtifact(ModuleRevisionId mrid) {
        Artifact artifact = getDefaultMetadataArtifact(mrid);
        try {
            // we need to provide an artifact origin to be sure we do not end up in a stack overflow
            // if the cache pattern is using original name, and the substitution thus trying to get
            // the saved artifact origin value which in turns calls this method
            return getLockStrategy().lockArtifact(artifact, 
                getArchiveFileInCache(artifact, getDefaultMetadataArtifactOrigin(mrid)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // reset interrupt status 
            throw new RuntimeException("operation interrupted");
        }
    }

    private void unlockMetadataArtifact(ModuleRevisionId mrid) {
        Artifact artifact = getDefaultMetadataArtifact(mrid);
        getLockStrategy().unlockArtifact(artifact, 
            getArchiveFileInCache(artifact, getDefaultMetadataArtifactOrigin(mrid)));
    }
    
    
    private ArtifactOrigin getDefaultMetadataArtifactOrigin(ModuleRevisionId mrid) {
        // it's important to say the origin is not local to make sure it won't ever be used for
        // anything else than original token
        return new ArtifactOrigin(
            DefaultArtifact.newIvyArtifact(mrid, null), false, getIvyFileInCache(mrid).getPath());
    }
    
    private Artifact getDefaultMetadataArtifact(ModuleRevisionId mrid) {
        return new DefaultArtifact(mrid, new Date(), "metadata", "metadata", "ivy", true);
    }

    // not used any more, but maybe useful for finer grain locking when downloading artifacts
//    private boolean lockArtifact(Artifact artifact) {
//        try {
//            return getLockStrategy().lockArtifact(artifact, 
//                getArchiveFileInCache(artifact, null));
//        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt(); // reset interrupt status 
//            throw new RuntimeException("operation interrupted");
//        }
//    }
//    
//    private void unlockArtifact(Artifact artifact) {
//        getLockStrategy().unlockArtifact(artifact, getArchiveFileInCache(artifact, null));
//    }
    
    public Artifact getOriginalMetadataArtifact(Artifact moduleArtifact) {
        return DefaultArtifact.cloneWithAnotherType(
            moduleArtifact, moduleArtifact.getType() + ".original");
    }
    

    private boolean isOriginalMetadataArtifact(Artifact artifact) {
        return artifact.isMetadata() 
            && artifact.getType().endsWith(".original");
    }

    private boolean isChanging(
            DependencyDescriptor dd, ModuleRevisionId requestedRevisionId, 
            CacheMetadataOptions options) {
        return dd.isChanging() 
            || getChangingMatcher(options).matches(requestedRevisionId.getRevision());
    }

    private Matcher getChangingMatcher(CacheMetadataOptions options) {
        String changingPattern = options.getChangingPattern() != null 
                ? options.getChangingPattern() : this.changingPattern;
        if (changingPattern == null) {
            return NoMatcher.INSTANCE;
        }
        String changingMatcherName = options.getChangingMatcherName() != null 
            ? options.getChangingMatcherName() : this.changingMatcherName;
        PatternMatcher matcher = settings.getMatcher(changingMatcherName);
        if (matcher == null) {
            throw new IllegalStateException("unknown matcher '" + changingMatcherName
                    + "'. It is set as changing matcher in " + this);
        }
        return matcher.getMatcher(changingPattern);
    }

    private boolean isCheckmodified(
            DependencyDescriptor dd, ModuleRevisionId requestedRevisionId, 
            CacheMetadataOptions options) {
        if (options.isCheckmodified() != null) {
            return options.isCheckmodified().booleanValue();
        }
        return isCheckmodified();
    }

    private boolean prepAndDeleteArtifact(final Artifact artifact, final CacheMetadataOptions options,
                                          final BackupResourceDownloader backupDownloader) throws IOException {
        final Artifact transformedArtifact =
            NameSpaceHelper.transform(artifact, options.getNamespace().getToSystemTransformer());
        final ArtifactOrigin origin = getSavedArtifactOrigin(transformedArtifact);
        final File artFile = getArchiveFileInCache(transformedArtifact, origin, false);
        if (artFile.exists()) {
            Message.debug("deleting " + artFile);
            if (!artFile.delete()) {
                // Old artifacts couldn't get deleted!
                // Restore the original ivy file so the next time we
                // resolve the old artifacts are deleted again
                backupDownloader.restore();
                Message.error("Couldn't delete outdated artifact from cache: " + artFile);
                return false;
            }
        }
        removeSavedArtifactOrigin(transformedArtifact);
        return true;
    }

    public void clean() {
        FileUtil.forceDelete(getBasedir());
    }

    public void dumpSettings() {
        Message.verbose("\t" + getName());
        Message.debug("\t\tivyPattern: " + getIvyPattern());
        Message.debug("\t\tartifactPattern: " + getArtifactPattern());
        Message.debug("\t\tlockingStrategy: " + getLockStrategy().getName());
        Message.debug("\t\tchangingPattern: " + getChangingPattern());
        Message.debug("\t\tchangingMatcher: " + getChangingMatcherName());
    }

    /**
     * Resource downloader which makes a copy of the previously existing file before overriding it.
     * <p>
     * The backup file can be restored or cleanuped later
     */
    private final class BackupResourceDownloader implements ResourceDownloader {
        
        private ResourceDownloader delegate;
        private File backup;
        private String originalPath;
        
        private BackupResourceDownloader(ResourceDownloader delegate) {
            this.delegate = delegate;
        }

        public void download(Artifact artifact, Resource resource, File dest) throws IOException {
            // keep a copy of the original file
            if (dest.exists()) {
                originalPath = dest.getAbsolutePath();
                backup = new File(dest.getAbsolutePath() + ".backup");
                FileUtil.copy(dest, backup, null, true);
            }
            delegate.download(artifact, resource, dest);
        }
        
        public void restore() throws IOException {
            if ((backup != null) && backup.exists()) {
                File original = new File(originalPath);
                FileUtil.copy(backup, original, null, true);
                backup.delete();
            }
        }
        
        public void cleanUp() {
            if ((backup != null) && backup.exists()) {
                backup.delete();
            }
        }
        
    }

}

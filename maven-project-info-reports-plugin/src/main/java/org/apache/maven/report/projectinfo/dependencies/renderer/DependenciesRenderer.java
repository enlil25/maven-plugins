package org.apache.maven.report.projectinfo.dependencies.renderer;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.FieldPosition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.doxia.parser.Parser;
import org.apache.maven.doxia.sink.Sink;
import org.apache.maven.model.License;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.report.projectinfo.ProjectInfoReportUtils;
import org.apache.maven.report.projectinfo.dependencies.Dependencies;
import org.apache.maven.report.projectinfo.dependencies.DependenciesReportConfiguration;
import org.apache.maven.report.projectinfo.dependencies.RepositoryUtils;
import org.apache.maven.reporting.AbstractMavenReportRenderer;
import org.apache.maven.settings.Settings;
import org.apache.maven.shared.dependency.tree.DependencyNode;
import org.apache.maven.shared.jar.JarData;
import org.codehaus.plexus.i18n.I18N;
import org.codehaus.plexus.util.StringUtils;

/**
 * Renderer the dependencies report.
 *
 * @version $Id$
 * @since 2.1
 */
public class DependenciesRenderer
    extends AbstractMavenReportRenderer
{
    /** URL for the 'icon_info_sml.gif' image */
    private static final String IMG_INFO_URL = "./images/icon_info_sml.gif";

    /** URL for the 'close.gif' image */
    private static final String IMG_CLOSE_URL = "./images/close.gif";

    /** Random used to generate a UID */
    private static final SecureRandom RANDOM;

    /** Used to format decimal values in the "Dependency File Details" table */
    protected static final DecimalFormat DEFAULT_DECIMAL_FORMAT = new DecimalFormat( "#,##0" );

    /** Used to format file length values */
    private static final DecimalFormat FILE_LENGTH_DECIMAL_FORMAT = new FileDecimalFormat();

    private static final Set JAR_SUBTYPE = new HashSet();

    private final Locale locale;

    private final DependencyNode dependencyTreeNode;

    private final Dependencies dependencies;

    private final DependenciesReportConfiguration configuration;

    private final I18N i18n;

    private final Log log;

    private final Settings settings;

    private final RepositoryUtils repoUtils;

    /**
     * Will be filled with license name / list of projects.
     */
    private Map licenseMap = new HashMap()
    {
        /** {@inheritDoc} */
        public Object put( Object key, Object value )
        {
            // handle multiple values as a list
            List valueList = (List) get( key );
            if ( valueList == null )
            {
                valueList = new ArrayList();
            }
            valueList.add( value );
            return super.put( key, valueList );
        }
    };

    private final MavenProjectBuilder mavenProjectBuilder;

    private final List remoteRepositories;

    private final ArtifactRepository localRepository;

    static
    {
        JAR_SUBTYPE.add( "jar" );
        JAR_SUBTYPE.add( "war" );
        JAR_SUBTYPE.add( "ear" );
        JAR_SUBTYPE.add( "sar" );
        JAR_SUBTYPE.add( "rar" );
        JAR_SUBTYPE.add( "par" );
        JAR_SUBTYPE.add( "ejb" );

        try
        {
            RANDOM = SecureRandom.getInstance( "SHA1PRNG" );
        }
        catch ( NoSuchAlgorithmException e )
        {
            throw new RuntimeException( e );
        }
    }

    /**
     * Default constructor.
     *
     * @param sink
     * @param locale
     * @param i18n
     * @param log
     * @param settings
     * @param dependencies
     * @param dependencyTreeNode
     * @param config
     * @param repoUtils
     * @param mavenProjectBuilder
     * @param remoteRepositories
     * @param localRepository
     */
    public DependenciesRenderer( Sink sink, Locale locale, I18N i18n, Log log, Settings settings, Dependencies dependencies,
                                 DependencyNode dependencyTreeNode, DependenciesReportConfiguration config,
                                 RepositoryUtils repoUtils, MavenProjectBuilder mavenProjectBuilder,
                                 List remoteRepositories, ArtifactRepository localRepository )
    {
        super( sink );

        this.locale = locale;
        this.i18n = i18n;
        this.log = log;
        this.settings = settings;
        this.dependencies = dependencies;
        this.dependencyTreeNode = dependencyTreeNode;
        this.repoUtils = repoUtils;
        this.configuration = config;
        this.mavenProjectBuilder = mavenProjectBuilder;
        this.remoteRepositories = remoteRepositories;
        this.localRepository = localRepository;

        // Using the right set of symbols depending of the locale
        DEFAULT_DECIMAL_FORMAT.setDecimalFormatSymbols( new DecimalFormatSymbols( locale ) );

        FILE_LENGTH_DECIMAL_FORMAT.setDecimalFormatSymbols( new DecimalFormatSymbols( locale ) );
    }

    // ----------------------------------------------------------------------
    // Public methods
    // ----------------------------------------------------------------------

    /** {@inheritDoc} */
    public String getTitle()
    {
        return getReportString( "report.dependencies.title" );
    }

    /** {@inheritDoc} */
    public void renderBody()
    {
        // Dependencies report

        if ( !dependencies.hasDependencies() )
        {
            startSection( getTitle() );

            // TODO: should the report just be excluded?
            paragraph( getReportString( "report.dependencies.nolist" ) );

            endSection();

            return;
        }

        // === Section: Project Dependencies.
        renderSectionProjectDependencies();

        // === Section: Project Transitive Dependencies.
        renderSectionProjectTransitiveDependencies();

        // === Section: Project Dependency Graph.
        renderSectionProjectDependencyGraph();

        // === Section: Licenses
        renderSectionDependencyLicenseListing();

        if ( configuration.getDependencyDetailsEnabled() )
        {
            // === Section: Dependency File Details.
            renderSectionDependencyFileDetails();
        }

        if ( configuration.getDependencyLocationsEnabled() )
        {
            // === Section: Dependency Repository Locations.
            renderSectionDependencyRepositoryLocations();
        }
    }

    // ----------------------------------------------------------------------
    // Private methods
    // ----------------------------------------------------------------------

    /**
     * @param withClassifier <code>true</code> to include the classifier column, <code>false</code> otherwise.
     * @param withOptional <code>true</code> to include the optional column, <code>false</code> otherwise.
     * @return the dependency table header with/without classifier/optional column
     * @see #getArtifactRow(Artifact, boolean, boolean)
     */
    private String[] getDependencyTableHeader( boolean withClassifier, boolean withOptional )
    {
        String groupId = getReportString( "report.dependencies.column.groupId" );
        String artifactId = getReportString( "report.dependencies.column.artifactId" );
        String version = getReportString( "report.dependencies.column.version" );
        String classifier = getReportString( "report.dependencies.column.classifier" );
        String type = getReportString( "report.dependencies.column.type" );
        String optional = getReportString( "report.dependencies.column.optional" );

        if ( withClassifier )
        {
            if ( withOptional )
            {
                return new String[] { groupId, artifactId, version, classifier, type, optional };
            }

            return new String[] { groupId, artifactId, version, classifier, type };
        }

        if ( withOptional )
        {
            return new String[] { groupId, artifactId, version, type, optional };
        }

        return new String[] { groupId, artifactId, version, type };
    }

    private void renderSectionProjectDependencies()
    {
        startSection( getTitle() );

        // collect dependencies by scope
        Map dependenciesByScope = dependencies.getDependenciesByScope( false );

        renderDependenciesForAllScopes( dependenciesByScope );

        endSection();
    }

    /**
     * @param dependenciesByScope map with supported scopes as key and a list of <code>Artifact</code> as values.
     * @see Artifact#SCOPE_COMPILE
     * @see Artifact#SCOPE_PROVIDED
     * @see Artifact#SCOPE_RUNTIME
     * @see Artifact#SCOPE_SYSTEM
     * @see Artifact#SCOPE_TEST
     */
    private void renderDependenciesForAllScopes( Map dependenciesByScope )
    {
        renderDependenciesForScope( Artifact.SCOPE_COMPILE, (List) dependenciesByScope.get( Artifact.SCOPE_COMPILE ) );
        renderDependenciesForScope( Artifact.SCOPE_RUNTIME, (List) dependenciesByScope.get( Artifact.SCOPE_RUNTIME ) );
        renderDependenciesForScope( Artifact.SCOPE_TEST, (List) dependenciesByScope.get( Artifact.SCOPE_TEST ) );
        renderDependenciesForScope( Artifact.SCOPE_PROVIDED,
                                    (List) dependenciesByScope.get( Artifact.SCOPE_PROVIDED ) );
        renderDependenciesForScope( Artifact.SCOPE_SYSTEM, (List) dependenciesByScope.get( Artifact.SCOPE_SYSTEM ) );
    }

    private void renderSectionProjectTransitiveDependencies()
    {
        Map dependenciesByScope = dependencies.getDependenciesByScope( true );

        startSection( getReportString( "report.transitivedependencies.title" ) );

        if ( dependenciesByScope.values().isEmpty() )
        {
            paragraph( getReportString( "report.transitivedependencies.nolist" ) );
        }
        else
        {
            paragraph( getReportString( "report.transitivedependencies.intro" ) );

            renderDependenciesForAllScopes( dependenciesByScope );
        }

        endSection();
    }

    private void renderSectionProjectDependencyGraph()
    {
        startSection( getReportString( "report.dependencies.graph.title" ) );

        // === SubSection: Dependency Tree
        renderSectionDependencyTree();

        endSection();
    }

    private void renderSectionDependencyTree()
    {
        sink.rawText(  getJavascript() );

        // for Dependencies Graph Tree
        startSection( getReportString( "report.dependencies.graph.tree.title" ) );

        sink.list();
        printDependencyListing( dependencyTreeNode );
        sink.list_();

        endSection();
    }

    private void renderSectionDependencyFileDetails()
    {
        startSection( getReportString( "report.dependencies.file.details.title" ) );

        List alldeps = dependencies.getAllDependencies();
        Collections.sort( alldeps, getArtifactComparator() );

        // i18n
        String filename = getReportString( "report.dependencies.file.details.column.file" );
        String size = getReportString( "report.dependencies.file.details.column.size" );
        String entries = getReportString( "report.dependencies.file.details.column.entries" );
        String classes = getReportString( "report.dependencies.file.details.column.classes" );
        String packages = getReportString( "report.dependencies.file.details.column.packages" );
        String jdkrev = getReportString( "report.dependencies.file.details.column.jdkrev" );
        String debug = getReportString( "report.dependencies.file.details.column.debug" );
        String sealed = getReportString( "report.dependencies.file.details.column.sealed" );

        int[] justification = new int[]{Parser.JUSTIFY_LEFT, Parser.JUSTIFY_RIGHT, Parser.JUSTIFY_RIGHT,
            Parser.JUSTIFY_RIGHT, Parser.JUSTIFY_RIGHT, Parser.JUSTIFY_CENTER, Parser.JUSTIFY_CENTER,
            Parser.JUSTIFY_CENTER};

        startTable();

        sink.tableRows( justification, true );

        TotalCell totaldeps = new TotalCell( DEFAULT_DECIMAL_FORMAT );
        TotalCell totaldepsize = new TotalCell( FILE_LENGTH_DECIMAL_FORMAT );
        TotalCell totalentries = new TotalCell( DEFAULT_DECIMAL_FORMAT );
        TotalCell totalclasses = new TotalCell( DEFAULT_DECIMAL_FORMAT );
        TotalCell  totalpackages = new TotalCell( DEFAULT_DECIMAL_FORMAT );
        double highestjdk = 0.0;
        TotalCell totaldebug = new TotalCell( DEFAULT_DECIMAL_FORMAT );
        TotalCell totalsealed = new TotalCell( DEFAULT_DECIMAL_FORMAT );

        boolean hasSealed = hasSealed( alldeps );

        // Table header

        String[] tableHeader;
        if ( hasSealed )
        {
            tableHeader = new String[] { filename, size, entries, classes, packages, jdkrev, debug, sealed };
        }
        else
        {
            tableHeader = new String[] { filename, size, entries, classes, packages, jdkrev, debug };
        }
        tableHeader( tableHeader );

        // Table rows

        for ( Iterator it = alldeps.iterator(); it.hasNext(); )
        {
            Artifact artifact = (Artifact) it.next();

            if ( artifact.getFile() == null )
            {
                log.error( "Artifact: " + artifact.getId() + " has no file." );
                continue;
            }

            File artifactFile = artifact.getFile();

            totaldeps.incrementTotal( artifact.getScope() );
            totaldepsize.addTotal( artifactFile.length(), artifact.getScope() );

            if ( JAR_SUBTYPE.contains( artifact.getType().toLowerCase() ) )
            {
                try
                {
                    JarData jarDetails = dependencies.getJarDependencyDetails( artifact );

                    String debugstr = "release";
                    if ( jarDetails.isDebugPresent() )
                    {
                        debugstr = "debug";
                        totaldebug.incrementTotal( artifact.getScope() );
                    }

                    totalentries.addTotal( jarDetails.getNumEntries(), artifact.getScope() );
                    totalclasses.addTotal( jarDetails.getNumClasses(), artifact.getScope() );
                    totalpackages.addTotal( jarDetails.getNumPackages(), artifact.getScope() );

                    try
                    {
                        if ( jarDetails.getJdkRevision() != null )
                        {
                            highestjdk = Math.max( highestjdk, Double.parseDouble( jarDetails.getJdkRevision() ) );
                        }
                    }
                    catch ( NumberFormatException e )
                    {
                        // ignore
                    }

                    if ( hasSealed )
                    {
                        String sealedstr = "";
                        if ( jarDetails.isSealed() )
                        {
                            sealedstr = "sealed";
                            totalsealed.incrementTotal( artifact.getScope() );
                        }

                        tableRow( new String[] {
                            artifactFile.getName(),
                            FILE_LENGTH_DECIMAL_FORMAT.format( artifactFile.length() ),
                            DEFAULT_DECIMAL_FORMAT.format( jarDetails.getNumEntries() ),
                            DEFAULT_DECIMAL_FORMAT.format( jarDetails.getNumClasses() ),
                            DEFAULT_DECIMAL_FORMAT.format( jarDetails.getNumPackages() ),
                            jarDetails.getJdkRevision(),
                            debugstr,
                            sealedstr } );
                    }
                    else
                    {
                        tableRow( new String[] {
                            artifactFile.getName(),
                            FILE_LENGTH_DECIMAL_FORMAT.format( artifactFile.length() ),
                            DEFAULT_DECIMAL_FORMAT.format( jarDetails.getNumEntries() ),
                            DEFAULT_DECIMAL_FORMAT.format( jarDetails.getNumClasses() ),
                            DEFAULT_DECIMAL_FORMAT.format( jarDetails.getNumPackages() ),
                            jarDetails.getJdkRevision(),
                            debugstr } );
                    }
                }
                catch ( IOException e )
                {
                    createExceptionInfoTableRow( artifact, artifactFile, e );
                }
            }
            else
            {
                tableRow( new String[] { artifactFile.getName(),
                    FILE_LENGTH_DECIMAL_FORMAT.format( artifactFile.length() ), "", "", "", "", "", "" } );
            }
        }

        // Total raw

        tableHeader[0] = getReportString( "report.dependencies.file.details.total" );
        tableHeader( tableHeader );
        if ( hasSealed )
        {
            tableRow( new String[] {
                totaldeps.toString(),
                totaldepsize.toString(),
                totalentries.toString(),
                totalclasses.toString(),
                totalpackages.toString(),
                String.valueOf( highestjdk ),
                totaldebug.toString(),
                totalsealed.toString()} );
        }
        else
        {
            tableRow( new String[] {
                totaldeps.toString(),
                totaldepsize.toString(),
                totalentries.toString(),
                totalclasses.toString(),
                totalpackages.toString(),
                String.valueOf( highestjdk ),
                totaldebug.toString()} );
        }

        sink.tableRows_();

        endTable();
        endSection();
    }

    private void createExceptionInfoTableRow( Artifact artifact, File artifactFile, Exception e )
    {
        tableRow( new String[]{artifact.getId(), artifactFile.getAbsolutePath(), e.getMessage(), "", "", "", "", ""} );
    }

    private void populateRepositoryMap( Map repos, List rowRepos )
    {
        Iterator it = rowRepos.iterator();
        while ( it.hasNext() )
        {
            ArtifactRepository repo = (ArtifactRepository) it.next();

            repos.put( repo.getId(), repo );
        }
    }

    private void blacklistRepositoryMap( Map repos, List repoUrlBlackListed )
    {
        for ( Iterator it = repos.keySet().iterator(); it.hasNext(); )
        {
            String key = (String) it.next();
            ArtifactRepository repo = (ArtifactRepository) repos.get( key );

            // ping repo
            if ( !repo.isBlacklisted() )
            {
                if ( !repoUrlBlackListed.contains( repo.getUrl() ) )
                {
                    try
                    {
                        URL repoUrl = new URL( repo.getUrl() );
                        if ( ProjectInfoReportUtils.getInputStream( repoUrl, settings ) == null )
                        {
                            log.warn( "The repository url '" + repoUrl + "' has no stream - Repository '"
                                + repo.getId() + "' will be blacklisted." );
                            repo.setBlacklisted( true );
                            repoUrlBlackListed.add( repo.getUrl() );
                        }
                    }
                    catch ( IOException e )
                    {
                        log.warn( "The repository url '" + repo.getUrl() + "' is invalid - Repository '" + repo.getId()
                            + "' will be blacklisted." );
                        repo.setBlacklisted( true );
                        repoUrlBlackListed.add( repo.getUrl() );
                    }
                }
                else
                {
                    repo.setBlacklisted( true );
                }
            }
            else
            {
                repoUrlBlackListed.add( repo.getUrl() );
            }
        }
    }

    private void renderSectionDependencyRepositoryLocations()
    {
        startSection( getReportString( "report.dependencies.repo.locations.title" ) );

        // Collect Alphabetical Dependencies
        List alldeps = dependencies.getAllDependencies();
        Collections.sort( alldeps, getArtifactComparator() );

        // Collect Repositories
        Map repoMap = new HashMap();

        populateRepositoryMap( repoMap, repoUtils.getRemoteArtifactRepositories() );
        for ( Iterator it = alldeps.iterator(); it.hasNext(); )
        {
            Artifact artifact = (Artifact) it.next();
            try
            {
                MavenProject artifactProject = repoUtils.getMavenProjectFromRepository( artifact );
                populateRepositoryMap( repoMap, artifactProject.getRemoteArtifactRepositories() );
            }
            catch ( ProjectBuildingException e )
            {
                log.warn( "Unable to create Maven project from repository.", e );
            }
        }

        List repoUrlBlackListed = new ArrayList();
        blacklistRepositoryMap( repoMap, repoUrlBlackListed );

        // i18n
        String repoid = getReportString( "report.dependencies.repo.locations.column.repoid" );
        String url = getReportString( "report.dependencies.repo.locations.column.url" );
        String release = getReportString( "report.dependencies.repo.locations.column.release" );
        String snapshot = getReportString( "report.dependencies.repo.locations.column.snapshot" );
        String blacklisted = getReportString( "report.dependencies.repo.locations.column.blacklisted" );
        String releaseEnabled = getReportString( "report.dependencies.repo.locations.cell.release.enabled" );
        String releaseDisabled = getReportString( "report.dependencies.repo.locations.cell.release.disabled" );
        String snapshotEnabled = getReportString( "report.dependencies.repo.locations.cell.snapshot.enabled" );
        String snapshotDisabled = getReportString( "report.dependencies.repo.locations.cell.snapshot.disabled" );
        String blacklistedEnabled = getReportString( "report.dependencies.repo.locations.cell.blacklisted.enabled" );
        String blacklistedDisabled = getReportString( "report.dependencies.repo.locations.cell.blacklisted.disabled" );
        String artifact = getReportString( "report.dependencies.repo.locations.column.artifact" );

        // Render Repository List

        startTable();

        // Table header

        String[] tableHeader;
        int[] justificationRepo;
        if ( repoUrlBlackListed.isEmpty() )
        {
            tableHeader = new String[] { repoid, url, release, snapshot };
            justificationRepo = new int[] {
                Parser.JUSTIFY_LEFT,
                Parser.JUSTIFY_LEFT,
                Parser.JUSTIFY_CENTER,
                Parser.JUSTIFY_CENTER };
        }
        else
        {
            tableHeader = new String[] { repoid, url, release, snapshot, blacklisted };
            justificationRepo = new int[] {
                Parser.JUSTIFY_LEFT,
                Parser.JUSTIFY_LEFT,
                Parser.JUSTIFY_CENTER,
                Parser.JUSTIFY_CENTER,
                Parser.JUSTIFY_CENTER };
        }

        sink.tableRows( justificationRepo, true );

        tableHeader( tableHeader );

        // Table rows

        for ( Iterator it = repoMap.keySet().iterator(); it.hasNext(); )
        {
            String key = (String) it.next();
            ArtifactRepository repo = (ArtifactRepository) repoMap.get( key );

            sink.tableRow();
            tableCell( repo.getId() );

            sink.tableCell();
            if ( repo.isBlacklisted() )
            {
                sink.text( repo.getUrl() );
            }
            else
            {
                sink.link( repo.getUrl() );
                sink.text( repo.getUrl() );
                sink.link_();
            }
            sink.tableCell_();

            ArtifactRepositoryPolicy releasePolicy = repo.getReleases();
            tableCell( releasePolicy.isEnabled() ? releaseEnabled : releaseDisabled );

            ArtifactRepositoryPolicy snapshotPolicy = repo.getSnapshots();
            tableCell( snapshotPolicy.isEnabled() ? snapshotEnabled : snapshotDisabled );

            if ( !repoUrlBlackListed.isEmpty() )
            {
                tableCell( repo.isBlacklisted() ? blacklistedEnabled : blacklistedDisabled );
            }
            sink.tableRow_();
        }

        sink.tableRows_();

        endTable();

        // Render Artifact Breakdown.

        sink.paragraph();
        sink.text( getReportString( "report.dependencies.repo.locations.artifact.breakdown" ) );
        sink.paragraph_();

        List repoIdList = new ArrayList( repoMap.keySet() );

        tableHeader = new String[repoIdList.size() + 1];
        justificationRepo = new int[repoIdList.size() + 1];

        tableHeader[0] = artifact;
        justificationRepo[0] = Parser.JUSTIFY_LEFT;

        int idnum = 1;
        for ( Iterator it = repoIdList.iterator(); it.hasNext(); )
        {
            String id = (String) it.next();
            tableHeader[idnum] = id;
            justificationRepo[idnum] = Parser.JUSTIFY_CENTER;
            idnum++;
        }

        Map totalByRepo = new HashMap();
        TotalCell totaldeps = new TotalCell( DEFAULT_DECIMAL_FORMAT );

        startTable();

        sink.tableRows( justificationRepo, true );

        tableHeader( tableHeader );

        for ( Iterator it = alldeps.iterator(); it.hasNext(); )
        {
            Artifact dependency = (Artifact) it.next();

            totaldeps.incrementTotal( dependency.getScope() );

            sink.tableRow();

            if ( !Artifact.SCOPE_SYSTEM.equals( dependency.getScope() ) )
            {

                tableCell( dependency.getId() );

                for ( Iterator itrepo = repoIdList.iterator(); itrepo.hasNext(); )
                {
                    String repokey = (String) itrepo.next();
                    ArtifactRepository repo = (ArtifactRepository) repoMap.get( repokey );

                    String depUrl = repoUtils.getDependencyUrlFromRepository( dependency, repo );

                    Integer old = (Integer)totalByRepo.get( repokey );
                    if ( old == null )
                    {
                        totalByRepo.put( repokey, new Integer( 0 ) );
                        old = new Integer( 0 );
                    }

                    boolean dependencyExists = false;
                    // check snapshots in snapshots repository only and releases in release repositories...
                    if ( ( dependency.isSnapshot() && repo.getSnapshots().isEnabled() )
                        || ( !dependency.isSnapshot() && repo.getReleases().isEnabled() ) )
                    {
                        dependencyExists = repoUtils.dependencyExistsInRepo( repo, dependency );
                    }

                    if ( dependencyExists )
                    {
                        sink.tableCell();
                        if ( StringUtils.isNotEmpty( depUrl ) )
                        {
                            sink.link( depUrl );
                        }
                        else
                        {
                            sink.text( depUrl );
                        }

                        sink.figure();
                        sink.figureCaption();
                        sink.text( "Found at " + repo.getUrl() );
                        sink.figureCaption_();
                        sink.figureGraphics( "images/icon_success_sml.gif" );
                        sink.figure_();

                        sink.link_();
                        sink.tableCell_();

                        totalByRepo.put( repokey, new Integer( old.intValue() + 1 ) );
                    }
                    else
                    {
                        tableCell( "-" );
                    }
                }
            }
            else
            {
                tableCell( dependency.getId() );

                for ( Iterator itrepo = repoIdList.iterator(); itrepo.hasNext(); )
                {
                    itrepo.next();

                    tableCell( "-" );
                }
            }

            sink.tableRow_();
        }

        // Total row

        //reused key
        tableHeader[0] = getReportString( "report.dependencies.file.details.total" );
        tableHeader( tableHeader );
        String[] totalRow = new String[repoIdList.size() + 1];
        totalRow[0] = totaldeps.toString();
        idnum = 1;
        for ( Iterator itrepo = repoIdList.iterator(); itrepo.hasNext(); )
        {
            String repokey = (String) itrepo.next();

            totalRow[idnum++] = totalByRepo.get( repokey ).toString();
        }

        tableRow( totalRow );

        sink.tableRows_();

        endTable();

        endSection();
    }

    private void renderSectionDependencyLicenseListing()
    {
        startSection( getReportString( "report.dependencies.graph.tables.licenses" ) );
        printGroupedLicenses();
        endSection();
    }

    private void renderDependenciesForScope( String scope, List artifacts )
    {
        if ( artifacts != null )
        {
            boolean withClassifier = hasClassifier( artifacts );
            boolean withOptional = hasOptional( artifacts );
            String[] tableHeader = getDependencyTableHeader( withClassifier, withOptional );

            // can't use straight artifact comparison because we want optional last
            Collections.sort( artifacts, getArtifactComparator() );

            startSection( scope );

            paragraph( getReportString( "report.dependencies.intro." + scope ) );

            startTable();
            tableHeader( tableHeader );
            for ( Iterator iterator = artifacts.iterator(); iterator.hasNext(); )
            {
                Artifact artifact = (Artifact) iterator.next();

                tableRow( getArtifactRow( artifact, withClassifier, withOptional ) );
            }
            endTable();

            endSection();
        }
    }

    private Comparator getArtifactComparator()
    {
        return new Comparator()
        {
            public int compare( Object o1, Object o2 )
            {
                Artifact a1 = (Artifact) o1;
                Artifact a2 = (Artifact) o2;

                // put optional last
                if ( a1.isOptional() && !a2.isOptional() )
                {
                    return +1;
                }
                else if ( !a1.isOptional() && a2.isOptional() )
                {
                    return -1;
                }
                else
                {
                    return a1.compareTo( a2 );
                }
            }
        };
    }

    /**
     * @param artifact not null
     * @param withClassifier <code>true</code> to include the classifier column, <code>false</code> otherwise.
     * @param withOptional <code>true</code> to include the optional column, <code>false</code> otherwise.
     * @return the dependency row with/without classifier/optional column
     * @see #getDependencyTableHeader(boolean, boolean)
     */
    private String[] getArtifactRow( Artifact artifact, boolean withClassifier, boolean withOptional )
    {
        String isOptional = artifact.isOptional() ? getReportString( "report.dependencies.column.isOptional" )
            : getReportString( "report.dependencies.column.isNotOptional" );

        String url = ProjectInfoReportUtils.getArtifactUrl( artifact, mavenProjectBuilder, remoteRepositories, localRepository );
        String artifactIdCell = ProjectInfoReportUtils.getArtifactIdCell( artifact.getArtifactId(), url );

        if ( withClassifier )
        {
            if ( withOptional )
            {
                return new String[] {
                    artifact.getGroupId(),
                    artifactIdCell,
                    artifact.getVersion(),
                    artifact.getClassifier(),
                    artifact.getType(),
                    isOptional };
            }

            return new String[] {
                artifact.getGroupId(),
                artifactIdCell,
                artifact.getVersion(),
                artifact.getClassifier(),
                artifact.getType() };
        }

        if ( withOptional )
        {
            return new String[] {
                artifact.getGroupId(),
                artifactIdCell,
                artifact.getVersion(),
                artifact.getType(),
                isOptional };
        }

        return new String[] {
            artifact.getGroupId(),
            artifactIdCell,
            artifact.getVersion(),
            artifact.getType()};
    }

    private void printDependencyListing( DependencyNode node )
    {
        Artifact artifact = node.getArtifact();
        String id = artifact.getId();
        String dependencyDetailId = getUUID();
        String imgId = getUUID();

        sink.listItem();

        sink.paragraph();
        sink.text( id + ( StringUtils.isNotEmpty( artifact.getScope() ) ? " (" + artifact.getScope() + ") " : " " ) );
        sink.rawText( "<img id=\"" + imgId + "\" src=\"" + IMG_INFO_URL
            + "\" alt=\"Information\" onclick=\"toggleDependencyDetail( '" + dependencyDetailId + "', '" + imgId
            + "' );\" style=\"cursor: pointer;vertical-align:text-bottom;\"></img>" );
        sink.paragraph_();

        printDescriptionsAndURLs( node, dependencyDetailId );

        if ( !node.getChildren().isEmpty() )
        {
            boolean toBeIncluded = false;
            List subList = new ArrayList();
            for ( Iterator deps = node.getChildren().iterator(); deps.hasNext(); )
            {
                DependencyNode dep = (DependencyNode) deps.next();

                if ( !dependencies.getAllDependencies().contains( dep.getArtifact() ) )
                {
                    continue;
                }

                subList.add( dep );
                toBeIncluded = true;
            }

            if ( toBeIncluded )
            {
                sink.list();
                for ( Iterator deps = subList.iterator(); deps.hasNext(); )
                {
                    DependencyNode dep = (DependencyNode) deps.next();

                    printDependencyListing( dep );
                }
                sink.list_();
            }
        }

        sink.listItem_();
    }

    private void printDescriptionsAndURLs( DependencyNode node, String uid )
    {
        Artifact artifact = node.getArtifact();
        String id = artifact.getId();
        String unknownLicenseMessage = getReportString( "report.dependencies.graph.tables.unknown" );

        sink.rawText( "<div id=\"" + uid + "\" style=\"display:none\">" );

        sink.table();

        if ( !Artifact.SCOPE_SYSTEM.equals( artifact.getScope() ) )
        {
            try
            {
                MavenProject artifactProject = repoUtils.getMavenProjectFromRepository( artifact );
                String artifactDescription = artifactProject.getDescription();
                String artifactUrl = artifactProject.getUrl();
                String artifactName = artifactProject.getName();
                List licenses = artifactProject.getLicenses();

                sink.tableRow();
                sink.tableHeaderCell();
                sink.text( artifactName );
                sink.tableHeaderCell_();
                sink.tableRow_();

                sink.tableRow();
                sink.tableCell();

                sink.paragraph();
                sink.bold();
                sink.text( getReportString( "report.dependencies.column.description" ) + ": " );
                sink.bold_();
                if ( StringUtils.isNotEmpty( artifactDescription ) )
                {
                    sink.text( artifactDescription );
                }
                else
                {
                    sink.text( getReportString( "report.index.nodescription" ) );
                }
                sink.paragraph_();

                if ( StringUtils.isNotEmpty( artifactUrl ) )
                {
                    sink.paragraph();
                    sink.bold();
                    sink.text( getReportString( "report.dependencies.column.url" ) + ": " );
                    sink.bold_();
                    if ( ProjectInfoReportUtils.isArtifactUrlValid( artifactUrl ) )
                    {
                        sink.link( artifactUrl );
                        sink.text( artifactUrl );
                        sink.link_();
                    }
                    else
                    {
                        sink.text( artifactUrl );
                    }
                    sink.paragraph_();
                }

                sink.paragraph();
                sink.bold();
                sink.text( getReportString( "report.license.title" ) + ": " );
                sink.bold_();
                if ( !licenses.isEmpty() )
                {
                    for ( Iterator iter = licenses.iterator(); iter.hasNext(); )
                    {
                        License element = (License) iter.next();
                        String licenseName = element.getName();
                        String licenseUrl = element.getUrl();

                        if ( licenseUrl != null )
                        {
                            sink.link( licenseUrl );
                        }
                        sink.text( licenseName );

                        if ( licenseUrl != null )
                        {
                            sink.link_();
                        }

                        licenseMap.put( licenseName, artifactName );
                    }
                }
                else
                {
                    sink.text( getReportString( "report.license.nolicense" ) );

                    licenseMap.put( unknownLicenseMessage, artifactName );
                }
                sink.paragraph_();
            }
            catch ( ProjectBuildingException e )
            {
                log.error( "ProjectBuildingException error : ", e );
            }
        }
        else
        {
            sink.tableRow();
            sink.tableHeaderCell();
            sink.text( id );
            sink.tableHeaderCell_();
            sink.tableRow_();

            sink.tableRow();
            sink.tableCell();

            sink.paragraph();
            sink.bold();
            sink.text( getReportString( "report.dependencies.column.description" ) + ": " );
            sink.bold_();
            sink.text( getReportString( "report.index.nodescription" ) );
            sink.paragraph_();

            if ( artifact.getFile() != null )
            {
                sink.paragraph();
                sink.bold();
                sink.text( getReportString( "report.dependencies.column.url" ) + ": " );
                sink.bold_();
                sink.text( artifact.getFile().getAbsolutePath() );
                sink.paragraph_();
            }
        }

        sink.tableCell_();
        sink.tableRow_();

        sink.table_();

        sink.rawText( "</div>" );
    }

    private void printGroupedLicenses()
    {
        for ( Iterator iter = licenseMap.keySet().iterator(); iter.hasNext(); )
        {
            String licenseName = (String) iter.next();
            sink.paragraph();
            sink.bold();
            if ( StringUtils.isEmpty( licenseName ) )
            {
                sink.text( i18n.getString( "project-info-report", locale, "report.dependencies.unamed" ) );
            }
            else
            {
                sink.text( licenseName );
            }
            sink.text( ": " );
            sink.bold_();

            List projects = (List) licenseMap.get( licenseName );
            Collections.sort( projects );

            for ( Iterator iterator = projects.iterator(); iterator.hasNext(); )
            {
                String projectName = (String) iterator.next();
                sink.text( projectName );
                if ( iterator.hasNext() )
                {
                    sink.text( "," );
                }
                sink.text( " " );
            }

            sink.paragraph_();
        }
    }

    private String getReportString( String key )
    {
        return i18n.getString( "project-info-report", locale, key );
    }

    /**
     * @param artifacts not null
     * @return <code>true</code> if one artifact in the list has a classifier, <code>false</code> otherwise.
     */
    private boolean hasClassifier( List artifacts )
    {
        for ( Iterator iterator = artifacts.iterator(); iterator.hasNext(); )
        {
            Artifact artifact = (Artifact) iterator.next();

            if ( StringUtils.isNotEmpty(  artifact.getClassifier() ) )
            {
                return true;
            }
        }

        return false;
    }

    /**
     * @param artifacts not null
     * @return <code>true</code> if one artifact in the list is optional, <code>false</code> otherwise.
     */
    private boolean hasOptional( List artifacts )
    {
        for ( Iterator iterator = artifacts.iterator(); iterator.hasNext(); )
        {
            Artifact artifact = (Artifact) iterator.next();

            if ( artifact.isOptional() )
            {
                return true;
            }
        }

        return false;
    }

    /**
     * @param artifacts not null
     * @return <code>true</code> if one artifact in the list is sealed, <code>false</code> otherwise.
     */
    private boolean hasSealed( List artifacts )
    {
        for ( Iterator it = artifacts.iterator(); it.hasNext(); )
        {
            Artifact artifact = (Artifact) it.next();

            // TODO site:run Why do we need to resolve this...
            if ( artifact.getFile() == null && !Artifact.SCOPE_SYSTEM.equals( artifact.getScope() ) )
            {
                try
                {
                    repoUtils.resolve( artifact );
                }
                catch ( ArtifactResolutionException e )
                {
                    log.error( "Artifact: " + artifact.getId() + " has no file.", e );
                    continue;
                }
                catch ( ArtifactNotFoundException e )
                {
                    if ( ( dependencies.getProject().getGroupId().equals( artifact.getGroupId() ) )
                        && ( dependencies.getProject().getArtifactId().equals( artifact.getArtifactId() ) )
                        && ( dependencies.getProject().getVersion().equals( artifact.getVersion() ) ) )
                    {
                        log.warn( "The artifact of this project has never been deployed." );
                    }
                    else
                    {
                        log.error( "Artifact: " + artifact.getId() + " has no file.", e );
                    }

                    continue;
                }
            }

            if ( JAR_SUBTYPE.contains( artifact.getType().toLowerCase() ) )
            {
                try
                {
                    JarData jarDetails = dependencies.getJarDependencyDetails( artifact );
                    if ( jarDetails.isSealed() )
                    {
                        return true;
                    }
                }
                catch ( IOException e )
                {
                    log.error( "IOException: " + e.getMessage(), e );
                }
            }
        }
        return false;
    }

    /**
     * @return an HTML script tag with the Javascript used by the dependencies report.
     */
    private static String getJavascript()
    {
        StringBuffer sb = new StringBuffer();
        sb.append( "<script language=\"javascript\" type=\"text/javascript\">" ).append( "\n" );
        sb.append( "      function toggleDependencyDetail( divId, imgId )" ).append( "\n" );
        sb.append( "      {" ).append( "\n" );
        sb.append( "        var div = document.getElementById( divId );" ).append( "\n" );
        sb.append( "        var img = document.getElementById( imgId );" ).append( "\n" );
        sb.append( "        if( div.style.display == '' )" ).append( "\n" );
        sb.append( "        {" ).append( "\n" );
        sb.append( "          div.style.display = 'none';" ).append( "\n" );
        sb.append( "          img.src='" + IMG_INFO_URL + "';" ).append( "\n" );
        sb.append( "        }" ).append( "\n" );
        sb.append( "        else" ).append( "\n" );
        sb.append( "        {" ).append( "\n" );
        sb.append( "          div.style.display = '';" ).append( "\n" );
        sb.append( "          img.src='" + IMG_CLOSE_URL + "';" ).append( "\n" );
        sb.append( "        }" ).append( "\n" );
        sb.append( "      }" ).append( "\n" );
        sb.append( "</script>" ).append( "\n" );

        return sb.toString();
    }

    /**
     * @return a valid HTML ID respecting
     * <a href="http://www.w3.org/TR/xhtml1/#C_8">XHTML 1.0 section C.8. Fragment Identifiers</a>
     */
    private static String getUUID()
    {
        return "_" + Math.abs( RANDOM.nextInt() );
    }

    /**
     * Formats file length with the associated unit (GB, MB, KB) and using the pattern
     * <code>########.00</code> by default.
     */
    static class FileDecimalFormat extends DecimalFormat
    {
        private static final long serialVersionUID = 4062503546523610081L;

        /**
         * Default constructor
         */
        public FileDecimalFormat()
        {
            super( "#,###.00" );
        }

        /** {@inheritDoc} */
        public StringBuffer format( long fs, StringBuffer result, FieldPosition fieldPosition )
        {
            if ( fs > 1024 * 1024 * 1024 )
            {
                result = super.format( (float) fs / ( 1024 * 1024 * 1024 ), result, fieldPosition );
                result.append( " GB" );
                return result;
            }

            if ( fs > 1024 * 1024 )
            {
                result = super.format( (float) fs / ( 1024 * 1024 ), result, fieldPosition );
                result.append( " MB" );
                return result;
            }

            result = super.format( (float) fs / ( 1024 ), result, fieldPosition );
            result.append( " KB" );
            return result;
        }
    }

    /**
     * Combine total and total by scope in a cell.
     */
    static class TotalCell
    {
        final DecimalFormat decimalFormat;

        long total = 0;

        long totalCompileScope = 0;

        long totalTestScope = 0;

        long totalRuntimeScope = 0;

        long totalProvidedScope = 0;

        long totalSystemScope = 0;

        TotalCell( DecimalFormat decimalFormat )
        {
            this.decimalFormat = decimalFormat;
        }

        void incrementTotal( String scope )
        {
            addTotal( 1, scope );
        }

        void addTotal( long add, String scope )
        {
            total += add;

            if ( Artifact.SCOPE_COMPILE.equals( scope ) )
            {
                totalCompileScope += add;
            }
            else if ( Artifact.SCOPE_TEST.equals( scope ) )
            {
                totalTestScope += add;
            }
            else if ( Artifact.SCOPE_RUNTIME.equals( scope ) )
            {
                totalRuntimeScope += add;
            }
            else if ( Artifact.SCOPE_PROVIDED.equals( scope ) )
            {
                totalProvidedScope += add;
            }
            else if ( Artifact.SCOPE_SYSTEM.equals( scope ) )
            {
                totalSystemScope += add;
            }
        }

        /** {@inheritDoc} */
        public String toString()
        {
            StringBuffer sb = new StringBuffer();
            sb.append( decimalFormat.format( total ) );
            sb.append( " (" );
            if ( totalCompileScope > 0 )
            {
                sb.append( Artifact.SCOPE_COMPILE ).append( ": " );
                sb.append( decimalFormat.format( totalCompileScope ) );
            }
            if ( totalTestScope > 0 )
            {
                if ( totalCompileScope > 0 )
                {
                    sb.append( ", " );
                }
                sb.append( Artifact.SCOPE_TEST ).append( ": " );
                sb.append( decimalFormat.format( totalTestScope ) );
            }
            if ( totalRuntimeScope > 0 )
            {
                if ( totalCompileScope > 0 || totalTestScope > 0 )
                {
                    sb.append( ", " );
                }
                sb.append( Artifact.SCOPE_RUNTIME ).append( ": " );
                sb.append( decimalFormat.format( totalRuntimeScope ) );
            }
            if ( totalProvidedScope > 0 )
            {
                if ( totalCompileScope > 0 || totalTestScope > 0 || totalRuntimeScope > 0 )
                {
                    sb.append( ", " );
                }
                sb.append( Artifact.SCOPE_PROVIDED ).append( ": " );
                sb.append( decimalFormat.format( totalProvidedScope ) );
            }
            if ( totalSystemScope > 0 )
            {
                if ( totalCompileScope > 0 || totalTestScope > 0 || totalRuntimeScope > 0 || totalProvidedScope > 0 )
                {
                    sb.append( ", " );
                }
                sb.append( Artifact.SCOPE_SYSTEM ).append( ": " );
                sb.append( decimalFormat.format( totalSystemScope ) );
            }
            sb.append( ")" );

            return sb.toString();
        }
    }
}

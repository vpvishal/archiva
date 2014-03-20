package org.apache.archiva.metadata.repository.cassandra;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import me.prettyprint.cassandra.serializers.StringSerializer;
import me.prettyprint.hector.api.Keyspace;
import me.prettyprint.hector.api.beans.ColumnSlice;
import me.prettyprint.hector.api.beans.OrderedRows;
import me.prettyprint.hector.api.beans.Row;
import me.prettyprint.hector.api.exceptions.HInvalidRequestException;
import me.prettyprint.hector.api.factory.HFactory;
import me.prettyprint.hector.api.mutation.MutationResult;
import me.prettyprint.hector.api.query.QueryResult;
import org.apache.archiva.configuration.ArchivaConfiguration;
import org.apache.archiva.metadata.model.ArtifactMetadata;
import org.apache.archiva.metadata.model.FacetedMetadata;
import org.apache.archiva.metadata.model.MetadataFacet;
import org.apache.archiva.metadata.model.MetadataFacetFactory;
import org.apache.archiva.metadata.model.ProjectMetadata;
import org.apache.archiva.metadata.model.ProjectVersionMetadata;
import org.apache.archiva.metadata.model.ProjectVersionReference;
import org.apache.archiva.metadata.repository.MetadataRepository;
import org.apache.archiva.metadata.repository.MetadataRepositoryException;
import org.apache.archiva.metadata.repository.MetadataResolutionException;
import org.apache.archiva.metadata.repository.cassandra.model.ArtifactMetadataModel;
import org.apache.archiva.metadata.repository.cassandra.model.Namespace;
import org.apache.archiva.metadata.repository.cassandra.model.Project;
import org.apache.archiva.metadata.repository.cassandra.model.Repository;
import org.apache.commons.lang.StringUtils;
import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.PersistenceException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author Olivier Lamy
 * @since 2.0.0
 */
public class CassandraMetadataRepository
    implements MetadataRepository
{

    private Logger logger = LoggerFactory.getLogger( getClass() );

    private ArchivaConfiguration configuration;

    private final Map<String, MetadataFacetFactory> metadataFacetFactories;

    private CassandraArchivaManager cassandraArchivaManager;

    public CassandraMetadataRepository( Map<String, MetadataFacetFactory> metadataFacetFactories,
                                        ArchivaConfiguration configuration,
                                        CassandraArchivaManager cassandraArchivaManager )
    {
        this.metadataFacetFactories = metadataFacetFactories;
        this.configuration = configuration;
        this.cassandraArchivaManager = cassandraArchivaManager;
    }


    /**
     * if the repository doesn't exist it will be created
     *
     * @param repositoryId
     * @return
     */
    public Repository getOrCreateRepository( String repositoryId )
        throws MetadataRepositoryException
    {
        Keyspace keyspace = cassandraArchivaManager.getKeyspace();
        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, StringSerializer.get(), StringSerializer.get(),
                                     StringSerializer.get() ) //
            .setColumnFamily( cassandraArchivaManager.getRepositoryFamilyName() ) //
            .setColumnNames( "id", "name" ) //
            .addEqualsExpression( "id", repositoryId ) //
            .execute();

        if ( result.get().getCount() < 1 )
        {
            // we need to create the repository
            Repository repository = new Repository( repositoryId );

            try
            {
                MutationResult mutationResult = HFactory.createMutator( keyspace, StringSerializer.get() ) //
                    //  values
                    .addInsertion( repositoryId, //
                                   cassandraArchivaManager.getRepositoryFamilyName(), //
                                   CassandraUtils.column( "id", repository.getId() ) ) //
                    .addInsertion( repositoryId, //
                                   cassandraArchivaManager.getRepositoryFamilyName(), //
                                   CassandraUtils.column( "name", repository.getName() ) ) //
                    .execute();
                return repository;
            }
            catch ( HInvalidRequestException e )
            {
                logger.error( e.getMessage(), e );
                throw new MetadataRepositoryException( e.getMessage(), e );
            }

        }

        return new Repository( result.get().getList().get( 0 ).getColumnSlice().getColumnByName( "id" ).getValue() );
    }


    protected Repository getRepository( String repositoryId )
        throws MetadataRepositoryException
    {
        Keyspace keyspace = cassandraArchivaManager.getKeyspace();
        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, StringSerializer.get(), StringSerializer.get(),
                                     StringSerializer.get() ) //
            .setColumnFamily( cassandraArchivaManager.getRepositoryFamilyName() ) //
            .setColumnNames( "id", "name" ) //
            .addEqualsExpression( "id", repositoryId ) //
            .execute();
        return ( result.get().getCount() > 0 ) ? new Repository( repositoryId ) : null;
    }

    @Override
    public void updateNamespace( String repositoryId, String namespaceId )
        throws MetadataRepositoryException
    {
        updateOrAddNamespace( repositoryId, namespaceId );

    }

    public Namespace updateOrAddNamespace( String repositoryId, String namespaceId )
        throws MetadataRepositoryException
    {
        try
        {
            Repository repository = getOrCreateRepository( repositoryId );

            Keyspace keyspace = cassandraArchivaManager.getKeyspace();

            String key =
                new Namespace.KeyBuilder().withNamespace( namespaceId ).withRepositoryId( repositoryId ).build();

            Namespace namespace = getNamespace( repositoryId, namespaceId );
            if ( namespace == null )
            {
                namespace = new Namespace( namespaceId, repository );
                HFactory.createMutator( keyspace, StringSerializer.get() )
                    //  values
                    .addInsertion( key, //
                                   cassandraArchivaManager.getNamespaceFamilyName(), //
                                   CassandraUtils.column( "name", namespace.getName() ) ) //
                    .addInsertion( key, //
                                   cassandraArchivaManager.getNamespaceFamilyName(), //
                                   CassandraUtils.column( "repositoryId", repository.getId() ) ) //
                    .execute();
            }

            return namespace;
        }
        catch ( HInvalidRequestException e )
        {
            logger.error( e.getMessage(), e );
            throw new MetadataRepositoryException( e.getMessage(), e );
        }
    }

    protected Namespace getNamespace( String repositoryId, String namespaceId )
    {
        Keyspace keyspace = cassandraArchivaManager.getKeyspace();
        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, //
                                     StringSerializer.get(), //
                                     StringSerializer.get(), //
                                     StringSerializer.get() ) //
            .setColumnFamily( cassandraArchivaManager.getNamespaceFamilyName() ) //
            .setColumnNames( "repositoryId", "name" ) //
            .addEqualsExpression( "repositoryId", repositoryId ) //
            .addEqualsExpression( "name", namespaceId ) //
            .execute();
        if ( result.get().getCount() > 0 )
        {
            ColumnSlice<String, String> columnSlice = result.get().getList().get( 0 ).getColumnSlice();
            return new Namespace( columnSlice.getColumnByName( "name" ).getValue(), //
                                  new Repository( columnSlice.getColumnByName( "repositoryId" ).getValue() ) );

        }
        return null;
    }


    @Override
    public void removeNamespace( String repositoryId, String namespaceId )
        throws MetadataRepositoryException
    {
        try
        {
            String key =
                new Namespace.KeyBuilder().withNamespace( namespaceId ).withRepositoryId( repositoryId ).build();

            MutationResult result =
                HFactory.createMutator( cassandraArchivaManager.getKeyspace(), new StringSerializer() ) //
                    .addDeletion( key, cassandraArchivaManager.getNamespaceFamilyName() ) //
                    .execute();


        }
        catch ( HInvalidRequestException e )
        {
            logger.error( e.getMessage(), e );
            throw new MetadataRepositoryException( e.getMessage(), e );
        }
    }


    @Override
    public void removeRepository( final String repositoryId )
        throws MetadataRepositoryException
    {

        // FIXME remove all datas attached to the repositoryId

        // retrieve and delete all namespace with this repositoryId

        List<String> namespacesKey = new ArrayList<String>();

        Keyspace keyspace = cassandraArchivaManager.getKeyspace();
        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, //
                                     StringSerializer.get(), //
                                     StringSerializer.get(), //
                                     StringSerializer.get() ) //
            .setColumnFamily( cassandraArchivaManager.getNamespaceFamilyName() ) //
            .setColumnNames( "repositoryId", "name" ) //
            .addEqualsExpression( "repositoryId", repositoryId ) //
            .execute();

        for ( Row<String, String, String> row : result.get().getList() )
        {
            namespacesKey.add( row.getKey() );
        }

        HFactory.createMutator( cassandraArchivaManager.getKeyspace(), new StringSerializer() ) //
            .addDeletion( namespacesKey, cassandraArchivaManager.getNamespaceFamilyName() ) //
            .execute();

        //delete repositoryId
        HFactory.createMutator( cassandraArchivaManager.getKeyspace(), new StringSerializer() ) //
            .addDeletion( repositoryId, cassandraArchivaManager.getRepositoryFamilyName() ) //
            .execute();

/*
        final List<ArtifactMetadataModel> artifactMetadataModels = new ArrayList<ArtifactMetadataModel>();

        // remove data related to the repository
        this.getArtifactMetadataModelEntityManager().visitAll( new Function<ArtifactMetadataModel, Boolean>()
        {
            @Override
            public Boolean apply( ArtifactMetadataModel artifactMetadataModel )
            {
                if ( artifactMetadataModel != null )
                {
                    if ( StringUtils.equals( artifactMetadataModel.getRepositoryId(), repositoryId ) )
                    {
                        artifactMetadataModels.add( artifactMetadataModel );
                    }
                }
                return Boolean.TRUE;
            }
        } );

        getArtifactMetadataModelEntityManager().remove( artifactMetadataModels );

        final List<Namespace> namespaces = new ArrayList<Namespace>();

        getNamespaceEntityManager().visitAll( new Function<Namespace, Boolean>()
        {
            @Override
            public Boolean apply( Namespace namespace )
            {
                if ( namespace != null )
                {
                    if ( StringUtils.equals( namespace.getRepository().getId(), repositoryId ) )
                    {
                        namespaces.add( namespace );
                    }
                }
                return Boolean.TRUE;
            }
        } );

        getNamespaceEntityManager().remove( namespaces );

        final List<Project> projects = new ArrayList<Project>();
        getProjectEntityManager().visitAll( new Function<Project, Boolean>()
        {
            @Override
            public Boolean apply( Project project )
            {
                if ( project != null )
                {
                    if ( StringUtils.equals( project.getNamespace().getRepository().getId(), repositoryId ) )
                    {
                        projects.add( project );
                    }
                }
                return Boolean.TRUE;
            }
        } );

        getProjectEntityManager().remove( projects );

        // TODO  cleanup or not
        //final List<MetadataFacetModel> metadataFacetModels = new ArrayList<MetadataFacetModel>(  );
        //getMetadataFacetModelEntityManager().visitAll( new Function<MetadataFacetModel, Boolean>()

        final List<ProjectVersionMetadataModel> projectVersionMetadataModels =
            new ArrayList<ProjectVersionMetadataModel>();

        getProjectVersionMetadataModelEntityManager().visitAll( new Function<ProjectVersionMetadataModel, Boolean>()
        {
            @Override
            public Boolean apply( ProjectVersionMetadataModel projectVersionMetadataModel )
            {
                if ( projectVersionMetadataModel != null )
                {
                    if ( StringUtils.equals( projectVersionMetadataModel.getNamespace().getRepository().getId(),
                                             repositoryId ) )
                    {
                        projectVersionMetadataModels.add( projectVersionMetadataModel );
                    }
                }
                return Boolean.TRUE;
            }
        } );

        getProjectVersionMetadataModelEntityManager().remove( projectVersionMetadataModels );

        Repository repository = getRepositoryEntityManager().get( repositoryId );
        if ( repository != null )
        {
            getRepositoryEntityManager().remove( repository );
        }

    */
    }

    @Override
    public Collection<String> getRepositories()
        throws MetadataRepositoryException
    {
        try
        {
            logger.debug( "getRepositories" );

            final QueryResult<OrderedRows<String, String, String>> cResult = //
                HFactory.createRangeSlicesQuery( cassandraArchivaManager.getKeyspace(), //
                                                 StringSerializer.get(), //
                                                 StringSerializer.get(), //
                                                 StringSerializer.get() ) //
                    .setColumnFamily( "repository" ) //
                    .setColumnNames( "name" ) //
                    .setRange( null, null, false, Integer.MAX_VALUE ) //
                    .execute();

            List<String> repoIds = new ArrayList<String>( cResult.get().getCount() );

            for ( Row<String, String, String> row : cResult.get() )
            {
                repoIds.add( row.getColumnSlice().getColumnByName( "name" ).getValue() );
            }

            return repoIds;
        }
        catch ( PersistenceException e )
        {
            throw new MetadataRepositoryException( e.getMessage(), e );
        }

    }


    @Override
    public Collection<String> getRootNamespaces( final String repoId )
        throws MetadataResolutionException
    {
        Keyspace keyspace = cassandraArchivaManager.getKeyspace();
        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, //
                                     StringSerializer.get(), //
                                     StringSerializer.get(), //
                                     StringSerializer.get() ) //
            .setColumnFamily( "namespace" ) //
            .setColumnNames( "name" ) //
            .addEqualsExpression( "repositoryId", repoId ) //
            .execute();

        Set<String> namespaces = new HashSet<String>( result.get().getCount() );

        for ( Row<String, String, String> row : result.get() )
        {
            namespaces.add(
                StringUtils.substringBefore( row.getColumnSlice().getColumnByName( "name" ).getValue(), "." ) );
        }

        return namespaces;
    }


    @Override
    public Collection<String> getNamespaces( final String repoId, final String namespaceId )
        throws MetadataResolutionException
    {
        Keyspace keyspace = cassandraArchivaManager.getKeyspace();
        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, //
                                     StringSerializer.get(), //
                                     StringSerializer.get(), //
                                     StringSerializer.get() ) //
            .setColumnFamily( cassandraArchivaManager.getNamespaceFamilyName() ) //
            .setColumnNames( "name" ) //
            .addEqualsExpression( "repositoryId", repoId ) //
            .execute();

        List<String> namespaces = new ArrayList<String>( result.get().getCount() );

        for ( Row<String, String, String> row : result.get() )
        {
            String currentNamespace = row.getColumnSlice().getColumnByName( "name" ).getValue();
            if ( StringUtils.startsWith( currentNamespace, namespaceId ) && ( StringUtils.length( currentNamespace )
                > StringUtils.length( namespaceId ) ) )
            {
                // store after namespaceId '.' but before next '.'
                // call org namespace org.apache.maven.shared -> stored apache

                String calledNamespace = StringUtils.endsWith( namespaceId, "." ) ? namespaceId : namespaceId + ".";
                String storedNamespace = StringUtils.substringAfter( currentNamespace, calledNamespace );

                storedNamespace = StringUtils.substringBefore( storedNamespace, "." );

                namespaces.add( storedNamespace );
            }
        }

        return namespaces;

    }


    public List<String> getNamespaces( final String repoId )
        throws MetadataResolutionException
    {
        Keyspace keyspace = cassandraArchivaManager.getKeyspace();
        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, //
                                     StringSerializer.get(), //
                                     StringSerializer.get(), //
                                     StringSerializer.get() ) //
            .setColumnFamily( cassandraArchivaManager.getNamespaceFamilyName() ) //
            .setColumnNames( "name" ) //
            .addEqualsExpression( "repositoryId", repoId ) //
            .execute();

        List<String> namespaces = new ArrayList<String>( result.get().getCount() );

        for ( Row<String, String, String> row : result.get() )
        {
            namespaces.add( row.getColumnSlice().getColumnByName( "name" ).getValue() );
        }

        return namespaces;
    }


    @Override
    public void updateProject( String repositoryId, ProjectMetadata projectMetadata )
        throws MetadataRepositoryException
    {
        Keyspace keyspace = cassandraArchivaManager.getKeyspace();

        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, //
                                     StringSerializer.get(), //
                                     StringSerializer.get(), //
                                     StringSerializer.get() ) //
            .setColumnFamily( cassandraArchivaManager.getProjectFamilyName() ) //
            .setColumnNames( "projectId" ) //
            .addEqualsExpression( "repositoryId", repositoryId ) //
            .addEqualsExpression( "namespaceId", projectMetadata.getNamespace() ) //
            .addEqualsExpression( "projectId", projectMetadata.getId() ) //
            .execute();

        // project exists ? if yes return
        if ( result.get().getCount() > 0 )
        {
            return;
        }

        Namespace namespace = updateOrAddNamespace( repositoryId, projectMetadata.getNamespace() );

        String key =
            new Project.KeyBuilder().withProjectId( projectMetadata.getId() ).withNamespace( namespace ).build();

        HFactory.createMutator( keyspace, StringSerializer.get() )
            //  values
            .addInsertion( key, //
                           cassandraArchivaManager.getProjectFamilyName(), //
                           CassandraUtils.column( "projectId", projectMetadata.getId() ) ) //
            .addInsertion( key, //
                           cassandraArchivaManager.getProjectFamilyName(), //
                           CassandraUtils.column( "repositoryId", repositoryId ) ) //
            .addInsertion( key, //
                           cassandraArchivaManager.getProjectFamilyName(), //
                           CassandraUtils.column( "namespaceId", projectMetadata.getNamespace() ) )//
            .execute();
    }

    @Override
    public Collection<String> getProjects( final String repoId, final String namespace )
        throws MetadataResolutionException
    {

        Keyspace keyspace = cassandraArchivaManager.getKeyspace();

        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, //
                                     StringSerializer.get(), //
                                     StringSerializer.get(), //
                                     StringSerializer.get() ) //
            .setColumnFamily( cassandraArchivaManager.getProjectFamilyName() ) //
            .setColumnNames( "projectId" ) //
            .addEqualsExpression( "repositoryId", repoId ) //
            .addEqualsExpression( "namespaceId", namespace ) //
            .execute();

        final Set<String> projects = new HashSet<String>( result.get().getCount() );

        for ( Row<String, String, String> row : result.get() )
        {
            projects.add( row.getColumnSlice().getColumnByName( "projectId" ).getValue() );
        }

        return projects;
    }

    @Override
    public void removeProject( final String repositoryId, final String namespaceId, final String projectId )
        throws MetadataRepositoryException
    {

/*        // cleanup ArtifactMetadataModel
        final List<ArtifactMetadataModel> artifactMetadataModels = new ArrayList<ArtifactMetadataModel>();

        getArtifactMetadataModelEntityManager().visitAll( new Function<ArtifactMetadataModel, Boolean>()
        {
            @Override
            public Boolean apply( ArtifactMetadataModel artifactMetadataModel )
            {
                if ( artifactMetadataModel != null )
                {
                    if ( StringUtils.equals( artifactMetadataModel.getRepositoryId(), repositoryId )
                        && StringUtils.equals( artifactMetadataModel.getNamespace(), namespaceId )
                        && StringUtils.equals( artifactMetadataModel.getProject(), projectId ) )
                    {
                        artifactMetadataModels.add( artifactMetadataModel );
                    }
                }
                return Boolean.TRUE;
            }
        } );

        getArtifactMetadataModelEntityManager().remove( artifactMetadataModels );

        Namespace namespace = new Namespace( namespaceId, new Repository( repositoryId ) );

        final List<ProjectVersionMetadataModel> projectVersionMetadataModels =
            new ArrayList<ProjectVersionMetadataModel>();

        getProjectVersionMetadataModelEntityManager().visitAll( new Function<ProjectVersionMetadataModel, Boolean>()
        {
            @Override
            public Boolean apply( ProjectVersionMetadataModel projectVersionMetadataModel )
            {
                if ( projectVersionMetadataModel != null )
                {
                    if ( StringUtils.equals( repositoryId,
                                             projectVersionMetadataModel.getNamespace().getRepository().getName() )
                        && StringUtils.equals( namespaceId, projectVersionMetadataModel.getNamespace().getName() )
                        && StringUtils.equals( projectId, projectVersionMetadataModel.getProjectId() ) )
                    {
                        projectVersionMetadataModels.add( projectVersionMetadataModel );
                    }
                }
                return Boolean.TRUE;
            }
        } );

        if ( !projectVersionMetadataModels.isEmpty() )
        {
            getProjectVersionMetadataModelEntityManager().remove( projectVersionMetadataModels );
        }

        String key = new Project.KeyBuilder().withNamespace( namespace ).withProjectId( projectId ).build();

        Project project = getProjectEntityManager().get( key );
        if ( project == null )
        {
            logger.debug( "removeProject notfound" );
            return;
        }
        logger.debug( "removeProject {}", project );

        getProjectEntityManager().remove( project );*/
    }

    @Override
    public Collection<String> getProjectVersions( final String repoId, final String namespace, final String projectId )
        throws MetadataResolutionException
    {
        return null;
/*        final Set<String> versions = new HashSet<String>();
        getProjectVersionMetadataModelEntityManager().visitAll( new Function<ProjectVersionMetadataModel, Boolean>()
        {
            @Override
            public Boolean apply( ProjectVersionMetadataModel projectVersionMetadataModel )
            {
                if ( projectVersionMetadataModel != null )
                {
                    if ( StringUtils.equals( repoId,
                                             projectVersionMetadataModel.getNamespace().getRepository().getName() )
                        && StringUtils.startsWith( projectVersionMetadataModel.getNamespace().getName(), namespace )
                        && StringUtils.equals( projectId, projectVersionMetadataModel.getProjectId() ) )
                    {
                        versions.add( projectVersionMetadataModel.getId() );
                    }
                }
                return Boolean.TRUE;
            }
        } );
        // FIXME use cql query
        getArtifactMetadataModelEntityManager().visitAll( new Function<ArtifactMetadataModel, Boolean>()
        {
            @Override
            public Boolean apply( ArtifactMetadataModel artifactMetadataModel )
            {
                if ( artifactMetadataModel != null )
                {
                    if ( StringUtils.equals( repoId, artifactMetadataModel.getRepositoryId() ) && StringUtils.equals(
                        namespace, artifactMetadataModel.getNamespace() ) && StringUtils.equals( projectId,
                                                                                                 artifactMetadataModel.getProject() ) )
                    {
                        versions.add( artifactMetadataModel.getProjectVersion() );
                    }
                }
                return Boolean.TRUE;
            }
        } );

        return versions;*/
    }

    @Override
    public void updateArtifact( String repositoryId, String namespaceId, String projectId, String projectVersion,
                                ArtifactMetadata artifactMeta )
        throws MetadataRepositoryException
    {
/*        String namespaceKey =
            new Namespace.KeyBuilder().withRepositoryId( repositoryId ).withNamespace( namespaceId ).build();
        // create the namespace if not exists
        Namespace namespace = getNamespaceEntityManager().get( namespaceKey );
        if ( namespace == null )
        {
            namespace = updateOrAddNamespace( repositoryId, namespaceId );
        }

        // create the project if not exist
        String projectKey = new Project.KeyBuilder().withNamespace( namespace ).withProjectId( projectId ).build();

        Project project = getProjectEntityManager().get( projectKey );
        if ( project == null )
        {
            project = new Project( projectKey, projectId, namespace );
            try
            {
                getProjectEntityManager().put( project );
            }
            catch ( PersistenceException e )
            {
                throw new MetadataRepositoryException( e.getMessage(), e );
            }
        }

        String key = new ArtifactMetadataModel.KeyBuilder().withNamespace( namespace ).withProject( projectId ).withId(
            artifactMeta.getId() ).withProjectVersion( projectVersion ).build();

        ArtifactMetadataModel artifactMetadataModel = getArtifactMetadataModelEntityManager().get( key );
        if ( artifactMetadataModel == null )
        {
            artifactMetadataModel = new ArtifactMetadataModel( key, artifactMeta.getId(), repositoryId, namespaceId,
                                                               artifactMeta.getProject(), projectVersion,
                                                               artifactMeta.getVersion(),
                                                               artifactMeta.getFileLastModified(),
                                                               artifactMeta.getSize(), artifactMeta.getMd5(),
                                                               artifactMeta.getSha1(), artifactMeta.getWhenGathered() );

        }
        else
        {
            artifactMetadataModel.setFileLastModified( artifactMeta.getFileLastModified().getTime() );
            artifactMetadataModel.setWhenGathered( artifactMeta.getWhenGathered().getTime() );
            artifactMetadataModel.setSize( artifactMeta.getSize() );
            artifactMetadataModel.setMd5( artifactMeta.getMd5() );
            artifactMetadataModel.setSha1( artifactMeta.getSha1() );
            artifactMetadataModel.setVersion( artifactMeta.getVersion() );
        }

        try
        {
            getArtifactMetadataModelEntityManager().put( artifactMetadataModel );
        }
        catch ( PersistenceException e )
        {
            throw new MetadataRepositoryException( e.getMessage(), e );
        }

        key = new ProjectVersionMetadataModel.KeyBuilder().withRepository( repositoryId ).withNamespace(
            namespace ).withProjectId( projectId ).withId( projectVersion ).build();

        ProjectVersionMetadataModel projectVersionMetadataModel =
            getProjectVersionMetadataModelEntityManager().get( key );

        if ( projectVersionMetadataModel == null )
        {
            projectVersionMetadataModel = new ProjectVersionMetadataModel();
            projectVersionMetadataModel.setRowId( key );
            projectVersionMetadataModel.setProjectId( projectId );
            projectVersionMetadataModel.setId( projectVersion );
            projectVersionMetadataModel.setNamespace( namespace );

            getProjectVersionMetadataModelEntityManager().put( projectVersionMetadataModel );

        }

        // now facets
        updateFacets( artifactMeta, artifactMetadataModel );*/

    }

    @Override
    public Collection<String> getArtifactVersions( final String repoId, final String namespace, final String projectId,
                                                   final String projectVersion )
        throws MetadataResolutionException
    {
/*        final Set<String> versions = new HashSet<String>();
        // FIXME use cql query
        getArtifactMetadataModelEntityManager().visitAll( new Function<ArtifactMetadataModel, Boolean>()
        {
            @Override
            public Boolean apply( ArtifactMetadataModel artifactMetadataModel )
            {
                if ( artifactMetadataModel != null )
                {
                    if ( StringUtils.equals( repoId, artifactMetadataModel.getRepositoryId() ) && StringUtils.equals(
                        namespace, artifactMetadataModel.getNamespace() ) && StringUtils.equals( projectId,
                                                                                                 artifactMetadataModel.getProject() )
                        && StringUtils.equals( projectVersion, artifactMetadataModel.getProjectVersion() ) )
                    {
                        versions.add( artifactMetadataModel.getVersion() );
                    }
                }
                return Boolean.TRUE;
            }
        } );

        return versions;*/
        return null;
    }

    /**
     * iterate over available facets to remove/add from the artifactMetadata
     *
     * @param facetedMetadata
     * @param artifactMetadataModel only use for the key
     */
    private void updateFacets( final FacetedMetadata facetedMetadata,
                               final ArtifactMetadataModel artifactMetadataModel )
    {

/*        for ( final String facetId : metadataFacetFactories.keySet() )
        {
            MetadataFacet metadataFacet = facetedMetadata.getFacet( facetId );
            if ( metadataFacet == null )
            {
                continue;
            }
            // clean first

            final List<MetadataFacetModel> metadataFacetModels = new ArrayList<MetadataFacetModel>();

            getMetadataFacetModelEntityManager().visitAll( new Function<MetadataFacetModel, Boolean>()
            {
                @Override
                public Boolean apply( MetadataFacetModel metadataFacetModel )
                {
                    ArtifactMetadataModel tmp = metadataFacetModel.getArtifactMetadataModel();
                    if ( StringUtils.equals( metadataFacetModel.getFacetId(), facetId ) && StringUtils.equals(
                        tmp.getRepositoryId(), artifactMetadataModel.getRepositoryId() ) && StringUtils.equals(
                        tmp.getNamespace(), artifactMetadataModel.getNamespace() ) && StringUtils.equals(
                        tmp.getProject(), artifactMetadataModel.getProject() ) )
                    {
                        metadataFacetModels.add( metadataFacetModel );
                    }
                    return Boolean.TRUE;
                }
            } );

            getMetadataFacetModelEntityManager().remove( metadataFacetModels );

            Map<String, String> properties = metadataFacet.toProperties();

            final List<MetadataFacetModel> metadataFacetModelsToAdd =
                new ArrayList<MetadataFacetModel>( properties.size() );

            for ( Map.Entry<String, String> entry : properties.entrySet() )
            {
                String key = new MetadataFacetModel.KeyBuilder().withKey( entry.getKey() ).withArtifactMetadataModel(
                    artifactMetadataModel ).withFacetId( facetId ).withName( metadataFacet.getName() ).build();
                MetadataFacetModel metadataFacetModel =
                    new MetadataFacetModel( key, artifactMetadataModel, facetId, entry.getKey(), entry.getValue(),
                                            metadataFacet.getName() );
                metadataFacetModelsToAdd.add( metadataFacetModel );
            }

            getMetadataFacetModelEntityManager().put( metadataFacetModelsToAdd );
        }*/
    }

    @Override
    public void updateProjectVersion( String repositoryId, String namespaceId, String projectId,
                                      ProjectVersionMetadata versionMetadata )
        throws MetadataRepositoryException
    {
/*        String namespaceKey =
            new Namespace.KeyBuilder().withRepositoryId( repositoryId ).withNamespace( namespaceId ).build();
        Namespace namespace = getNamespaceEntityManager().get( namespaceKey );
        if ( namespace == null )
        {
            namespace = updateOrAddNamespace( repositoryId, namespaceId );
        }

        String key = new Project.KeyBuilder().withNamespace( namespace ).withProjectId( projectId ).build();

        Project project = getProjectEntityManager().get( key );
        if ( project == null )
        {
            project = new Project( key, projectId, namespace );
            getProjectEntityManager().put( project );
        }

        // we don't test of repository and namespace really exist !
        key = new ProjectVersionMetadataModel.KeyBuilder().withRepository( repositoryId ).withNamespace(
            namespaceId ).withProjectId( projectId ).withId( versionMetadata.getId() ).build();

        ProjectVersionMetadataModel projectVersionMetadataModel =
            getProjectVersionMetadataModelEntityManager().get( key );

        if ( projectVersionMetadataModel == null )
        {
            projectVersionMetadataModel = getModelMapper().map( versionMetadata, ProjectVersionMetadataModel.class );
            projectVersionMetadataModel.setRowId( key );
        }
        projectVersionMetadataModel.setProjectId( projectId );
        projectVersionMetadataModel.setNamespace( new Namespace( namespaceId, new Repository( repositoryId ) ) );
        projectVersionMetadataModel.setCiManagement( versionMetadata.getCiManagement() );
        projectVersionMetadataModel.setIssueManagement( versionMetadata.getIssueManagement() );
        projectVersionMetadataModel.setOrganization( versionMetadata.getOrganization() );
        projectVersionMetadataModel.setScm( versionMetadata.getScm() );

        projectVersionMetadataModel.setMailingLists( versionMetadata.getMailingLists() );
        projectVersionMetadataModel.setDependencies( versionMetadata.getDependencies() );
        projectVersionMetadataModel.setLicenses( versionMetadata.getLicenses() );

        try
        {
            getProjectVersionMetadataModelEntityManager().put( projectVersionMetadataModel );

            ArtifactMetadataModel artifactMetadataModel = new ArtifactMetadataModel();
            artifactMetadataModel.setArtifactMetadataModelId(
                new ArtifactMetadataModel.KeyBuilder().withId( versionMetadata.getId() ).withRepositoryId(
                    repositoryId ).withNamespace( namespaceId ).withProjectVersion(
                    versionMetadata.getVersion() ).withProject( projectId ).build()
            );
            artifactMetadataModel.setRepositoryId( repositoryId );
            artifactMetadataModel.setNamespace( namespaceId );
            artifactMetadataModel.setProject( projectId );
            artifactMetadataModel.setProjectVersion( versionMetadata.getVersion() );
            artifactMetadataModel.setVersion( versionMetadata.getVersion() );
            // facets etc...
            updateFacets( versionMetadata, artifactMetadataModel );
        }
        catch ( PersistenceException e )
        {
            throw new MetadataRepositoryException( e.getMessage(), e );
        }*/
    }


    private static class BooleanHolder
    {
        private boolean value = false;
    }

    @Override
    public List<String> getMetadataFacets( final String repositoryId, final String facetId )
        throws MetadataRepositoryException
    {
/*        // FIXME use cql query !!
        final List<String> facets = new ArrayList<String>();
        this.getMetadataFacetModelEntityManager().visitAll( new Function<MetadataFacetModel, Boolean>()
        {
            @Override
            public Boolean apply( MetadataFacetModel metadataFacetModel )
            {
                if ( metadataFacetModel != null )
                {
                    if ( StringUtils.equals( metadataFacetModel.getArtifactMetadataModel().getRepositoryId(),
                                             repositoryId ) && StringUtils.equals( metadataFacetModel.getFacetId(),
                                                                                   facetId ) )
                    {
                        facets.add( metadataFacetModel.getName() );
                    }
                }
                return Boolean.TRUE;
            }
        } );

        return facets;*/
        return null;

    }

    @Override
    public boolean hasMetadataFacet( String repositoryId, String facetId )
        throws MetadataRepositoryException
    {
        return !getMetadataFacets( repositoryId, facetId ).isEmpty();
    }

    @Override
    public MetadataFacet getMetadataFacet( final String repositoryId, final String facetId, final String name )
        throws MetadataRepositoryException
    {
/*        // FIXME use cql query !!
        final List<MetadataFacetModel> facets = new ArrayList<MetadataFacetModel>();
        this.getMetadataFacetModelEntityManager().visitAll( new Function<MetadataFacetModel, Boolean>()
        {
            @Override
            public Boolean apply( MetadataFacetModel metadataFacetModel )
            {
                if ( metadataFacetModel != null )
                {
                    if ( StringUtils.equals( metadataFacetModel.getArtifactMetadataModel().getRepositoryId(),
                                             repositoryId ) && StringUtils.equals( metadataFacetModel.getFacetId(),
                                                                                   facetId ) && StringUtils.equals(
                        metadataFacetModel.getName(), name ) )
                    {
                        facets.add( metadataFacetModel );
                    }
                }
                return Boolean.TRUE;
            }
        } );

        if ( facets.isEmpty() )
        {
            return null;
        }

        MetadataFacetFactory metadataFacetFactory = metadataFacetFactories.get( facetId );
        if ( metadataFacetFactory == null )
        {
            return null;
        }
        MetadataFacet metadataFacet = metadataFacetFactory.createMetadataFacet( repositoryId, name );
        Map<String, String> map = new HashMap<String, String>( facets.size() );
        for ( MetadataFacetModel metadataFacetModel : facets )
        {
            map.put( metadataFacetModel.getKey(), metadataFacetModel.getValue() );
        }
        metadataFacet.fromProperties( map );
        return metadataFacet;*/
        return null;
    }

    @Override
    public void addMetadataFacet( String repositoryId, MetadataFacet metadataFacet )
        throws MetadataRepositoryException
    {
/*
        if ( metadataFacet == null )
        {
            return;
        }

        if ( metadataFacet.toProperties().isEmpty() )
        {
            String key = new MetadataFacetModel.KeyBuilder().withRepositoryId( repositoryId ).withFacetId(
                metadataFacet.getFacetId() ).withName( metadataFacet.getName() ).build();
            MetadataFacetModel metadataFacetModel = getMetadataFacetModelEntityManager().get( key );
            if ( metadataFacetModel == null )
            {
                metadataFacetModel = new MetadataFacetModel();
            }
            // we need to store the repositoryId
            ArtifactMetadataModel artifactMetadataModel = new ArtifactMetadataModel();
            artifactMetadataModel.setRepositoryId( repositoryId );
            metadataFacetModel.setArtifactMetadataModel( artifactMetadataModel );
            metadataFacetModel.setId( key );
            metadataFacetModel.setFacetId( metadataFacet.getFacetId() );
            metadataFacetModel.setName( metadataFacet.getName() );

            try
            {
                getMetadataFacetModelEntityManager().put( metadataFacetModel );
            }
            catch ( PersistenceException e )
            {
                throw new MetadataRepositoryException( e.getMessage(), e );
            }
        }
        else
        {
            for ( Map.Entry<String, String> entry : metadataFacet.toProperties().entrySet() )
            {

                String key = new MetadataFacetModel.KeyBuilder().withRepositoryId( repositoryId ).withFacetId(
                    metadataFacet.getFacetId() ).withName( metadataFacet.getName() ).withKey( entry.getKey() ).build();

                MetadataFacetModel metadataFacetModel = getMetadataFacetModelEntityManager().get( key );
                if ( metadataFacetModel == null )
                {
                    metadataFacetModel = new MetadataFacetModel();
                    // we need to store the repositoryId
                    ArtifactMetadataModel artifactMetadataModel = new ArtifactMetadataModel();
                    artifactMetadataModel.setRepositoryId( repositoryId );
                    metadataFacetModel.setArtifactMetadataModel( artifactMetadataModel );
                    metadataFacetModel.setId( key );
                    metadataFacetModel.setKey( entry.getKey() );
                    metadataFacetModel.setFacetId( metadataFacet.getFacetId() );
                    metadataFacetModel.setName( metadataFacet.getName() );
                }
                metadataFacetModel.setValue( entry.getValue() );
                try
                {
                    getMetadataFacetModelEntityManager().put( metadataFacetModel );
                }
                catch ( PersistenceException e )
                {
                    throw new MetadataRepositoryException( e.getMessage(), e );
                }

            }
        }*/
    }

    @Override
    public void removeMetadataFacets( final String repositoryId, final String facetId )
        throws MetadataRepositoryException
    {
/*        logger.debug( "removeMetadataFacets repositoryId: '{}', facetId: '{}'", repositoryId, facetId );
        final List<MetadataFacetModel> toRemove = new ArrayList<MetadataFacetModel>();

        // FIXME cql query
        getMetadataFacetModelEntityManager().visitAll( new Function<MetadataFacetModel, Boolean>()
        {
            @Override
            public Boolean apply( MetadataFacetModel metadataFacetModel )
            {
                if ( metadataFacetModel != null )
                {
                    if ( StringUtils.equals( metadataFacetModel.getArtifactMetadataModel().getRepositoryId(),
                                             repositoryId ) && StringUtils.equals( metadataFacetModel.getFacetId(),
                                                                                   facetId ) )
                    {
                        toRemove.add( metadataFacetModel );
                    }
                }
                return Boolean.TRUE;
            }
        } );
        logger.debug( "removeMetadataFacets repositoryId: '{}', facetId: '{}', toRemove: {}", repositoryId, facetId,
                      toRemove );
        getMetadataFacetModelEntityManager().remove( toRemove );*/
    }

    @Override
    public void removeMetadataFacet( final String repositoryId, final String facetId, final String name )
        throws MetadataRepositoryException
    {
/*        logger.debug( "removeMetadataFacets repositoryId: '{}', facetId: '{}'", repositoryId, facetId );
        final List<MetadataFacetModel> toRemove = new ArrayList<MetadataFacetModel>();

        // FIXME cql query
        getMetadataFacetModelEntityManager().visitAll( new Function<MetadataFacetModel, Boolean>()
        {
            @Override
            public Boolean apply( MetadataFacetModel metadataFacetModel )
            {
                if ( metadataFacetModel != null )
                {
                    if ( StringUtils.equals( metadataFacetModel.getArtifactMetadataModel().getRepositoryId(),
                                             repositoryId ) && StringUtils.equals( metadataFacetModel.getFacetId(),
                                                                                   facetId ) && StringUtils.equals(
                        metadataFacetModel.getName(), name ) )
                    {
                        toRemove.add( metadataFacetModel );
                    }
                }
                return Boolean.TRUE;
            }
        } );
        logger.debug( "removeMetadataFacets repositoryId: '{}', facetId: '{}', toRemove: {}", repositoryId, facetId,
                      toRemove );
        getMetadataFacetModelEntityManager().remove( toRemove );*/
    }

    @Override
    public List<ArtifactMetadata> getArtifactsByDateRange( final String repositoryId, final Date startTime,
                                                           final Date endTime )
        throws MetadataRepositoryException
    {

/*        final List<ArtifactMetadataModel> artifactMetadataModels = new ArrayList<ArtifactMetadataModel>();

        // FIXME cql query
        getArtifactMetadataModelEntityManager().visitAll( new Function<ArtifactMetadataModel, Boolean>()
        {
            @Override
            public Boolean apply( ArtifactMetadataModel artifactMetadataModel )
            {
                if ( artifactMetadataModel != null )
                {
                    if ( StringUtils.equals( artifactMetadataModel.getRepositoryId(), repositoryId )
                        && artifactMetadataModel.getNamespace() != null &&
                        artifactMetadataModel.getProject() != null && artifactMetadataModel.getId() != null )
                    {

                        Date when = artifactMetadataModel.getWhenGathered();
                        if ( ( startTime != null ? when.getTime() >= startTime.getTime() : true ) && ( endTime != null ?
                            when.getTime() <= endTime.getTime() : true ) )
                        {
                            logger.debug( "getArtifactsByDateRange visitAll found: {}", artifactMetadataModel );
                            artifactMetadataModels.add( artifactMetadataModel );
                        }
                    }
                }
                return Boolean.TRUE;
            }
        } );
        List<ArtifactMetadata> artifactMetadatas = new ArrayList<ArtifactMetadata>( artifactMetadataModels.size() );

        for ( ArtifactMetadataModel model : artifactMetadataModels )
        {
            ArtifactMetadata artifactMetadata = getModelMapper().map( model, ArtifactMetadata.class );
            populateFacets( artifactMetadata );
            artifactMetadatas.add( artifactMetadata );
        }

        // FIXME facets ?

        logger.debug( "getArtifactsByDateRange repositoryId: {}, startTime: {}, endTime: {}, artifactMetadatas: {}",
                      repositoryId, startTime, endTime, artifactMetadatas );

        return artifactMetadatas;*/
        return Collections.emptyList();
    }

    protected void populateFacets( final ArtifactMetadata artifactMetadata )
    {
/*        final List<MetadataFacetModel> metadataFacetModels = new ArrayList<MetadataFacetModel>();

        getMetadataFacetModelEntityManager().visitAll( new Function<MetadataFacetModel, Boolean>()
        {
            @Override
            public Boolean apply( MetadataFacetModel metadataFacetModel )
            {
                if ( metadataFacetModel != null )
                {
                    ArtifactMetadataModel artifactMetadataModel = metadataFacetModel.getArtifactMetadataModel();
                    if ( artifactMetadataModel != null )
                    {
                        if ( StringUtils.equals( artifactMetadata.getRepositoryId(),
                                                 artifactMetadataModel.getRepositoryId() ) && StringUtils.equals(
                            artifactMetadata.getNamespace(), artifactMetadataModel.getNamespace() )
                            && StringUtils.equals( artifactMetadata.getRepositoryId(),
                                                   artifactMetadataModel.getRepositoryId() ) && StringUtils.equals(
                            artifactMetadata.getProject(), artifactMetadataModel.getProject() ) && StringUtils.equals(
                            artifactMetadata.getId(), artifactMetadataModel.getId() ) )
                        {
                            metadataFacetModels.add( metadataFacetModel );
                        }
                    }
                }
                return Boolean.TRUE;
            }
        } );
        Map<String, Map<String, String>> facetValuesPerFacet = new HashMap<String, Map<String, String>>();

        for ( MetadataFacetModel model : metadataFacetModels )
        {
            Map<String, String> values = facetValuesPerFacet.get( model.getName() );
            if ( values == null )
            {
                values = new HashMap<String, String>();
            }
            values.put( model.getKey(), model.getValue() );
            facetValuesPerFacet.put( model.getName(), values );
        }

        for ( Map.Entry<String, Map<String, String>> entry : facetValuesPerFacet.entrySet() )
        {
            MetadataFacetFactory factory = metadataFacetFactories.get( entry.getKey() );
            if ( factory == null )
            {
                continue;
            }
            MetadataFacet metadataFacet =
                factory.createMetadataFacet( artifactMetadata.getRepositoryId(), entry.getKey() );
            metadataFacet.fromProperties( entry.getValue() );
            artifactMetadata.addFacet( metadataFacet );
        }*/
    }

    @Override
    public List<ArtifactMetadata> getArtifactsByChecksum( final String repositoryId, final String checksum )
        throws MetadataRepositoryException
    {
/*        final List<ArtifactMetadataModel> artifactMetadataModels = new ArrayList<ArtifactMetadataModel>();

        if ( logger.isDebugEnabled() )
        {
            logger.debug( "all ArtifactMetadataModel: {}", getArtifactMetadataModelEntityManager().getAll() );
        }

        // FIXME cql query
        getArtifactMetadataModelEntityManager().visitAll( new Function<ArtifactMetadataModel, Boolean>()
        {
            @Override
            public Boolean apply( ArtifactMetadataModel artifactMetadataModel )
            {
                if ( artifactMetadataModel != null )
                {
                    if ( StringUtils.equals( artifactMetadataModel.getRepositoryId(), repositoryId )
                        && artifactMetadataModel.getNamespace() != null &&
                        artifactMetadataModel.getProject() != null && artifactMetadataModel.getId() != null )
                    {

                        if ( StringUtils.equals( checksum, artifactMetadataModel.getMd5() ) || StringUtils.equals(
                            checksum, artifactMetadataModel.getSha1() ) )
                        {
                            artifactMetadataModels.add( artifactMetadataModel );
                        }
                    }
                }
                return Boolean.TRUE;
            }
        } );
        List<ArtifactMetadata> artifactMetadatas = new ArrayList<ArtifactMetadata>( artifactMetadataModels.size() );

        for ( ArtifactMetadataModel model : artifactMetadataModels )
        {
            ArtifactMetadata artifactMetadata = getModelMapper().map( model, ArtifactMetadata.class );
            populateFacets( artifactMetadata );
            artifactMetadatas.add( artifactMetadata );
        }

        logger.debug( "getArtifactsByChecksum repositoryId: {}, checksum: {}, artifactMetadatas: {}", repositoryId,
                      checksum, artifactMetadatas );

        return artifactMetadatas;*/
        return Collections.emptyList();
    }

    @Override
    public void removeArtifact( final String repositoryId, final String namespace, final String project,
                                final String version, final String id )
        throws MetadataRepositoryException
    {
/*        logger.debug( "removeArtifact repositoryId: '{}', namespace: '{}', project: '{}', version: '{}', id: '{}'",
                      repositoryId, namespace, project, version, id );
        String key =
            new ArtifactMetadataModel.KeyBuilder().withRepositoryId( repositoryId ).withNamespace( namespace ).withId(
                id ).withProjectVersion( version ).withProject( project ).build();

        ArtifactMetadataModel artifactMetadataModel = new ArtifactMetadataModel();
        artifactMetadataModel.setArtifactMetadataModelId( key );

        getArtifactMetadataModelEntityManager().remove( artifactMetadataModel );

        key =
            new ProjectVersionMetadataModel.KeyBuilder().withId( version ).withRepository( repositoryId ).withNamespace(
                namespace ).withProjectId( project ).build();

        ProjectVersionMetadataModel projectVersionMetadataModel = new ProjectVersionMetadataModel();
        projectVersionMetadataModel.setRowId( key );

        getProjectVersionMetadataModelEntityManager().remove( projectVersionMetadataModel );*/
    }

    @Override
    public void removeArtifact( ArtifactMetadata artifactMetadata, String baseVersion )
        throws MetadataRepositoryException
    {
        logger.debug( "removeArtifact repositoryId: '{}', namespace: '{}', project: '{}', version: '{}', id: '{}'",
                      artifactMetadata.getRepositoryId(), artifactMetadata.getNamespace(),
                      artifactMetadata.getProject(), baseVersion, artifactMetadata.getId() );
/*        String key =
            new ArtifactMetadataModel.KeyBuilder().withRepositoryId( artifactMetadata.getRepositoryId() ).withNamespace(
                artifactMetadata.getNamespace() ).withId( artifactMetadata.getId() ).withProjectVersion(
                baseVersion ).withProject( artifactMetadata.getProject() ).build();

        ArtifactMetadataModel artifactMetadataModel = new ArtifactMetadataModel();
        artifactMetadataModel.setArtifactMetadataModelId( key );

        getArtifactMetadataModelEntityManager().remove( artifactMetadataModel );*/
    }

    @Override
    public void removeArtifact( final String repositoryId, final String namespace, final String project,
                                final String version, final MetadataFacet metadataFacet )
        throws MetadataRepositoryException
    {
/*        final List<ArtifactMetadataModel> artifactMetadataModels = new ArrayList<ArtifactMetadataModel>();
        getArtifactMetadataModelEntityManager().visitAll( new Function<ArtifactMetadataModel, Boolean>()
        {
            @Override
            public Boolean apply( ArtifactMetadataModel artifactMetadataModel )
            {
                if ( artifactMetadataModel != null )
                {
                    if ( StringUtils.equals( repositoryId, artifactMetadataModel.getRepositoryId() )
                        && StringUtils.equals( namespace, artifactMetadataModel.getNamespace() ) && StringUtils.equals(
                        project, artifactMetadataModel.getProject() ) && StringUtils.equals( project,
                                                                                             artifactMetadataModel.getVersion() ) )
                    {
                        artifactMetadataModels.add( artifactMetadataModel );
                    }
                }
                return Boolean.TRUE;
            }
        } );
        getArtifactMetadataModelEntityManager().remove( artifactMetadataModels );*/

    }


    @Override
    public List<ArtifactMetadata> getArtifacts( final String repositoryId )
        throws MetadataRepositoryException
    {
/*        final List<ArtifactMetadataModel> artifactMetadataModels = new ArrayList<ArtifactMetadataModel>();
        // FIXME use cql query !
        getArtifactMetadataModelEntityManager().visitAll( new Function<ArtifactMetadataModel, Boolean>()
        {
            @Override
            public Boolean apply( ArtifactMetadataModel artifactMetadataModel )
           {
                if ( artifactMetadataModel != null )
                {
                    if ( StringUtils.equals( repositoryId, artifactMetadataModel.getRepositoryId() ) )
                    {
                        artifactMetadataModels.add( artifactMetadataModel );
                    }
                }

                return Boolean.TRUE;
            }
        } );

        List<ArtifactMetadata> artifactMetadatas = new ArrayList<ArtifactMetadata>( artifactMetadataModels.size() );

        for ( ArtifactMetadataModel model : artifactMetadataModels )
        {
            ArtifactMetadata artifactMetadata = getModelMapper().map( model, ArtifactMetadata.class );
            populateFacets( artifactMetadata );
            artifactMetadatas.add( artifactMetadata );
        }

        return artifactMetadatas;*/
        return Collections.emptyList();
    }

    @Override
    public ProjectMetadata getProject( final String repoId, final String namespace, final String id )
        throws MetadataResolutionException
    {

/*        //basically just checking it exists
        // FIXME use cql query

        final BooleanHolder booleanHolder = new BooleanHolder();

        getProjectEntityManager().visitAll( new Function<Project, Boolean>()
        {
            @Override
            public Boolean apply( Project project )
            {
                if ( project != null )
                {
                    if ( StringUtils.equals( repoId, project.getNamespace().getRepository().getName() )
                        && StringUtils.equals( namespace, project.getNamespace().getName() ) && StringUtils.equals( id,
                                                                                                                    project.getProjectId() ) )
                    {
                        booleanHolder.value = true;
                    }
                }
                return Boolean.TRUE;
            }
        } );

        if ( !booleanHolder.value )
        {
            return null;
        }

        ProjectMetadata projectMetadata = new ProjectMetadata();
        projectMetadata.setId( id );
        projectMetadata.setNamespace( namespace );

        logger.debug( "getProject repoId: {}, namespace: {}, projectId: {} -> {}", repoId, namespace, id,
                      projectMetadata );

        return projectMetadata;*/
        return null;
    }

    @Override
    public ProjectVersionMetadata getProjectVersion( final String repoId, final String namespace,
                                                     final String projectId, final String projectVersion )
        throws MetadataResolutionException
    {
/*        String key = new ProjectVersionMetadataModel.KeyBuilder().withRepository( repoId ).withNamespace(
            namespace ).withProjectId( projectId ).withId( projectVersion ).build();

        ProjectVersionMetadataModel projectVersionMetadataModel =
            getProjectVersionMetadataModelEntityManager().get( key );

        if ( projectVersionMetadataModel == null )
        {
            logger.debug(
                "getProjectVersion repoId: '{}', namespace: '{}', projectId: '{}', projectVersion: {} -> not found",
                repoId, namespace, projectId, projectVersion );
            return null;
        }

        ProjectVersionMetadata projectVersionMetadata =
            getModelMapper().map( projectVersionMetadataModel, ProjectVersionMetadata.class );

        logger.debug( "getProjectVersion repoId: '{}', namespace: '{}', projectId: '{}', projectVersion: {} -> {}",
                      repoId, namespace, projectId, projectVersion, projectVersionMetadata );

        projectVersionMetadata.setCiManagement( projectVersionMetadataModel.getCiManagement() );
        projectVersionMetadata.setIssueManagement( projectVersionMetadataModel.getIssueManagement() );
        projectVersionMetadata.setOrganization( projectVersionMetadataModel.getOrganization() );
        projectVersionMetadata.setScm( projectVersionMetadataModel.getScm() );

        // FIXME complete collections !!

        // facets
        final List<MetadataFacetModel> metadataFacetModels = new ArrayList<MetadataFacetModel>();
        // FIXME use cql query
        getMetadataFacetModelEntityManager().visitAll( new Function<MetadataFacetModel, Boolean>()
        {
            @Override
            public Boolean apply( MetadataFacetModel metadataFacetModel )
            {
                if ( metadataFacetModel != null )
                {
                    if ( StringUtils.equals( repoId, metadataFacetModel.getArtifactMetadataModel().getRepositoryId() )
                        && StringUtils.equals( namespace, metadataFacetModel.getArtifactMetadataModel().getNamespace() )
                        && StringUtils.equals( projectId, metadataFacetModel.getArtifactMetadataModel().getProject() )
                        && StringUtils.equals( projectVersion,
                                               metadataFacetModel.getArtifactMetadataModel().getProjectVersion() ) )
                    {
                        metadataFacetModels.add( metadataFacetModel );
                    }
                }
                return Boolean.TRUE;
            }
        } );
        Map<String, Map<String, String>> metadataFacetsPerFacetIds = new HashMap<String, Map<String, String>>();
        for ( MetadataFacetModel metadataFacetModel : metadataFacetModels )
        {

            Map<String, String> metaValues = metadataFacetsPerFacetIds.get( metadataFacetModel.getFacetId() );
            if ( metaValues == null )
            {
                metaValues = new HashMap<String, String>();
                metadataFacetsPerFacetIds.put( metadataFacetModel.getFacetId(), metaValues );
            }
            metaValues.put( metadataFacetModel.getKey(), metadataFacetModel.getValue() );

        }

        if ( !metadataFacetsPerFacetIds.isEmpty() )
        {
            for ( Map.Entry<String, Map<String, String>> entry : metadataFacetsPerFacetIds.entrySet() )
            {
                MetadataFacetFactory metadataFacetFactory = metadataFacetFactories.get( entry.getKey() );
                if ( metadataFacetFactory != null )
                {
                    MetadataFacet metadataFacet = metadataFacetFactory.createMetadataFacet( repoId, entry.getKey() );
                    metadataFacet.fromProperties( entry.getValue() );
                    projectVersionMetadata.addFacet( metadataFacet );
                }
            }
        }

        return projectVersionMetadata;*/
        return null;
    }


    @Override
    public Collection<ProjectVersionReference> getProjectReferences( String repoId, String namespace, String projectId,
                                                                     String projectVersion )
        throws MetadataResolutionException
    {
        // FIXME implement this
        return Collections.emptyList();
    }

    @Override
    public void removeProjectVersion( final String repoId, final String namespace, final String projectId,
                                      final String projectVersion )
        throws MetadataRepositoryException
    {
        /*
        final List<ArtifactMetadataModel> artifactMetadataModels = new ArrayList<ArtifactMetadataModel>();

        // FIXME use cql query

        getArtifactMetadataModelEntityManager().visitAll( new Function<ArtifactMetadataModel, Boolean>()
        {
            @Override
            public Boolean apply( ArtifactMetadataModel artifactMetadataModel )
            {
                if ( artifactMetadataModel != null )
                {
                    if ( StringUtils.equals( repoId, artifactMetadataModel.getRepositoryId() ) && StringUtils.equals(
                        namespace, artifactMetadataModel.getNamespace() ) && StringUtils.equals( projectId,
                                                                                                 artifactMetadataModel.getProject() )
                        && StringUtils.equals( projectVersion, artifactMetadataModel.getProjectVersion() ) )
                    {
                        artifactMetadataModels.add( artifactMetadataModel );
                    }
                }
                return Boolean.TRUE;
            }
        } );

        logger.debug( "removeProjectVersions:{}", artifactMetadataModels );
        if ( artifactMetadataModels.isEmpty() )
        {
            return;
        }

        getArtifactMetadataModelEntityManager().remove( artifactMetadataModels );

        String key = new ProjectVersionMetadataModel.KeyBuilder().withProjectId( projectId ).withId(
            projectVersion ).withRepository( repoId ).withNamespace( namespace ).build();

        ProjectVersionMetadataModel projectVersionMetadataModel = new ProjectVersionMetadataModel();
        projectVersionMetadataModel.setRowId( key );

        getProjectVersionMetadataModelEntityManager().remove( projectVersionMetadataModel );
        */
    }

    @Override
    public Collection<ArtifactMetadata> getArtifacts( final String repoId, final String namespace,
                                                      final String projectId, final String projectVersion )
        throws MetadataResolutionException
    {
        final List<ArtifactMetadataModel> artifactMetadataModels = new ArrayList<ArtifactMetadataModel>();
        /*
        // FIXME use cql query !
        getArtifactMetadataModelEntityManager().visitAll( new Function<ArtifactMetadataModel, Boolean>()
        {
            @Override
            public Boolean apply( ArtifactMetadataModel artifactMetadataModel )
            {
                if ( artifactMetadataModel != null )
                {
                    if ( StringUtils.equals( repoId, artifactMetadataModel.getRepositoryId() ) && StringUtils.equals(
                        namespace, artifactMetadataModel.getNamespace() ) && StringUtils.equals( projectId,
                                                                                                 artifactMetadataModel.getProject() )
                        && StringUtils.equals( projectVersion, artifactMetadataModel.getProjectVersion() ) )
                    {
                        artifactMetadataModels.add( artifactMetadataModel );
                    }
                }

                return Boolean.TRUE;
            }
        } );
        */
        List<ArtifactMetadata> artifactMetadatas = new ArrayList<ArtifactMetadata>( artifactMetadataModels.size() );
        /*
        for ( ArtifactMetadataModel model : artifactMetadataModels )
        {
            ArtifactMetadata artifactMetadata = getModelMapper().map( model, ArtifactMetadata.class );
            populateFacets( artifactMetadata );
            artifactMetadatas.add( artifactMetadata );
        }

        // retrieve facets
        final List<MetadataFacetModel> metadataFacetModels = new ArrayList<MetadataFacetModel>();
        getMetadataFacetModelEntityManager().visitAll( new Function<MetadataFacetModel, Boolean>()
        {
            @Override
            public Boolean apply( MetadataFacetModel metadataFacetModel )
            {
                if ( metadataFacetModel != null )
                {
                    if ( StringUtils.equals( repoId, metadataFacetModel.getArtifactMetadataModel().getRepositoryId() )
                        && StringUtils.equals( namespace, metadataFacetModel.getArtifactMetadataModel().getNamespace() )
                        && StringUtils.equals( projectId, metadataFacetModel.getArtifactMetadataModel().getProject() )
                        && StringUtils.equals( projectVersion,
                                               metadataFacetModel.getArtifactMetadataModel().getProjectVersion() ) )
                    {
                        metadataFacetModels.add( metadataFacetModel );
                    }

                }
                return Boolean.TRUE;
            }
        } );

        // rebuild MetadataFacet for artifacts

        for ( final ArtifactMetadata artifactMetadata : artifactMetadatas )
        {
            Iterable<MetadataFacetModel> metadataFacetModelIterable =
                Iterables.filter( metadataFacetModels, new Predicate<MetadataFacetModel>()
                {
                    @Override
                    public boolean apply( MetadataFacetModel metadataFacetModel )
                    {
                        if ( metadataFacetModel != null )
                        {
                            return StringUtils.equals( artifactMetadata.getVersion(),
                                                       metadataFacetModel.getArtifactMetadataModel().getVersion() );
                        }
                        return false;
                    }
                } );
            Iterator<MetadataFacetModel> iterator = metadataFacetModelIterable.iterator();
            Map<String, List<MetadataFacetModel>> metadataFacetValuesPerFacetId =
                new HashMap<String, List<MetadataFacetModel>>();
            while ( iterator.hasNext() )
            {
                MetadataFacetModel metadataFacetModel = iterator.next();
                List<MetadataFacetModel> values = metadataFacetValuesPerFacetId.get( metadataFacetModel.getName() );
                if ( values == null )
                {
                    values = new ArrayList<MetadataFacetModel>();
                    metadataFacetValuesPerFacetId.put( metadataFacetModel.getFacetId(), values );
                }
                values.add( metadataFacetModel );

            }

            for ( Map.Entry<String, List<MetadataFacetModel>> entry : metadataFacetValuesPerFacetId.entrySet() )
            {
                MetadataFacetFactory metadataFacetFactory = metadataFacetFactories.get( entry.getKey() );
                if ( metadataFacetFactory != null )
                {
                    List<MetadataFacetModel> facetModels = entry.getValue();
                    if ( !facetModels.isEmpty() )
                    {
                        MetadataFacet metadataFacet =
                            metadataFacetFactory.createMetadataFacet( repoId, facetModels.get( 0 ).getName() );
                        Map<String, String> props = new HashMap<String, String>( facetModels.size() );
                        for ( MetadataFacetModel metadataFacetModel : facetModels )
                        {
                            props.put( metadataFacetModel.getKey(), metadataFacetModel.getValue() );
                        }
                        metadataFacet.fromProperties( props );
                        artifactMetadata.addFacet( metadataFacet );
                    }
                }
            }


        }
        */
        return artifactMetadatas;
    }

    @Override
    public void save()
    {
        logger.trace( "save" );
    }

    @Override
    public void close()
        throws MetadataRepositoryException
    {
        logger.trace( "close" );
    }

    @Override
    public void revert()
    {
        logger.warn( "CassandraMetadataRepository cannot revert" );
    }

    @Override
    public boolean canObtainAccess( Class<?> aClass )
    {
        return false;
    }

    @Override
    public <T> T obtainAccess( Class<T> aClass )
        throws MetadataRepositoryException
    {
        throw new IllegalArgumentException(
            "Access using " + aClass + " is not supported on the cassandra metadata storage" );
    }


    private static class ModelMapperHolder
    {
        private static ModelMapper MODEL_MAPPER = new ModelMapper();
    }

    protected ModelMapper getModelMapper()
    {
        return ModelMapperHolder.MODEL_MAPPER;
    }
}
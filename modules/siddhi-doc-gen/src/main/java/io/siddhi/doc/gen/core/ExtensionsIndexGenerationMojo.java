/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.siddhi.doc.gen.core;

import io.siddhi.doc.gen.core.utils.Constants;
import io.siddhi.doc.gen.core.utils.DocumentationUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.util.List;

/**
 * Mojo for creating an index Siddhi documentation
 */
@Mojo(
        name = "generate-extensions-index",
        aggregator = true,
        defaultPhase = LifecyclePhase.INSTALL
)
public class ExtensionsIndexGenerationMojo extends AbstractMojo {
    /**
     * The maven project object
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject mavenProject;

    /**
     * The extension repository owner
     */
    @Parameter(property = "extension.repository.owner")
    private String extensionRepositoryOwner;

    /**
     * The extension repository names
     */
    @Parameter(property = "extension.repositories", required = true)
    private List<String> extensionRepositories;

    /**
     * The directory in which the index will be generated
     * Optional
     */
    @Parameter(property = "doc.gen.base.directory")
    private File docGenBaseDirectory;

    /**
     * The final output file name of the index
     */
    @Parameter(property = "index.gen.file.name")
    private String indexGenFileName;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        // Finding the root maven project
        MavenProject rootMavenProject = mavenProject;
        while (rootMavenProject.getParent() != null &&
                rootMavenProject.getParent().getBasedir() != null) {
            rootMavenProject = rootMavenProject.getParent();
        }

        // Checking if extension repositories and repository owner had been added in the configuration
        if (extensionRepositories == null || extensionRepositories.size() == 0) {
            throw new MojoExecutionException("extensionRepositories configuration is required to use goal " +
                    "generate-extensions-index");
        }

        // Setting the extension repository owner if not set by the user
        if (extensionRepositoryOwner == null) {
            extensionRepositoryOwner = Constants.GITHUB_OWNER_WSO2_EXTENSIONS;
        }

        // Setting the documentation output directory if not set by user
        String docGenBasePath;
        if (docGenBaseDirectory != null) {
            docGenBasePath = docGenBaseDirectory.getAbsolutePath();
        } else {
            docGenBasePath = rootMavenProject.getBasedir() + File.separator + Constants.DOCS_DIRECTORY;
        }

        // Setting the extension index output file name if not set by user
        if (indexGenFileName == null) {
            indexGenFileName = Constants.MARKDOWN_EXTENSIONS_INDEX_TEMPLATE;
        }

        // Creating a extensions index
        DocumentationUtils.createExtensionsIndex(
                extensionRepositories, extensionRepositoryOwner, docGenBasePath, indexGenFileName
        );
    }
}

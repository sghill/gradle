/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.caching.configuration

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.TestBuildCache
import spock.lang.Unroll

class BuildCacheConfigurationIntegrationTest extends AbstractIntegrationSpec {
    String cacheDir = temporaryFolder.file("cache-dir").createDir().absoluteFile.toURI().toString()
    def localBuildCache = new TestBuildCache(new File(new URI(cacheDir).path))

    def "can configure with settings.gradle"() {
        settingsFile << """
            buildCache {
                local(DirectoryBuildCache) {
                    directory = '$cacheDir'
                }
            }
        """

        buildFile << customTaskCode()

        expect:
        executer.withBuildCacheEnabled()
        succeeds("customTask")
        !localBuildCache.empty
    }

    def "can enable with settings.gradle"() {
        settingsFile << """
            gradle.startParameter.buildCacheEnabled = true
            buildCache {
                local(DirectoryBuildCache) {
                    directory = '$cacheDir'
                }
            }
        """

        buildFile << customTaskCode()

        expect:
        succeeds("customTask")
        !localBuildCache.empty
    }

    def "can configure with init script"() {
        def initScript = file("initBuildCache.gradle") << """
            gradle.settingsEvaluated { settings ->
                settings.buildCache {
                    local(DirectoryBuildCache) {
                        directory = '$cacheDir'
                    }
                }
            }
        """
        buildFile << customTaskCode()

        expect:
        executer.withBuildCacheEnabled().usingInitScript(initScript)
        succeeds("customTask")
        !localBuildCache.empty
    }

    def "can enable with init script"() {
        def initScript = file("initBuildCache.gradle") << """
            gradle.startParameter.buildCacheEnabled = true
            gradle.settingsEvaluated { settings ->
                settings.buildCache {
                    local(DirectoryBuildCache) {
                        directory = '$cacheDir'
                    }
                }
            }
        """
        buildFile << customTaskCode()

        expect:
        executer.usingInitScript(initScript)
        succeeds("customTask")
        !localBuildCache.empty
    }

    def "configuration in init script wins over settings.gradle"() {
        def initScript = file("initBuildCache.gradle") << """
            gradle.settingsEvaluated { settings ->
                settings.buildCache {
                    local(DirectoryBuildCache) {
                        directory = '$cacheDir'
                    }
                }
            }
        """
        settingsFile << """
            buildCache {
                local(DirectoryBuildCache) {
                    directory = "wrong"
                }
            }
        """
        buildFile << customTaskCode()

        expect:
        executer.withBuildCacheEnabled().usingInitScript(initScript)
        succeeds("customTask")
        !localBuildCache.empty
    }

    @Unroll
    def "last #cache cache configuration wins"() {
        settingsFile << """
            import org.gradle.caching.internal.NoOpBuildCacheService

            class CustomBuildCache extends AbstractBuildCache {}
            class AnotherBuildCache extends AbstractBuildCache {}

            class CustomBuildCacheFactory implements BuildCacheServiceFactory<CustomBuildCache> {
                @Override BuildCacheService createBuildCacheService(CustomBuildCache configuration, Describer describer) { 
                    new NoOpBuildCacheService() 
                }
            }

            class AnotherBuildCacheFactory implements BuildCacheServiceFactory<AnotherBuildCache> {
                @Override BuildCacheService createBuildCacheService(AnotherBuildCache configuration, Describer describer) { 
                    new NoOpBuildCacheService() 
                }
            }
            
            buildCache {
                registerBuildCacheService(CustomBuildCache, CustomBuildCacheFactory)
                registerBuildCacheService(AnotherBuildCache, AnotherBuildCacheFactory)

                $cache(CustomBuildCache)
                $cache(AnotherBuildCache)
            }
            
            assert buildCache.$cache instanceof AnotherBuildCache
        """
        if (expectDeprecation) {
            executer.expectDeprecationWarning()
        }

        expect:
        succeeds("help")

        where:
        cache    | expectDeprecation
        "local"  | true
        "remote" | false
    }

    def "disables remote cache with --offline"() {
        settingsFile << """
            import org.gradle.caching.internal.NoOpBuildCacheService
            class CustomBuildCache extends AbstractBuildCache {}
            
            class CustomBuildCacheFactory implements BuildCacheServiceFactory<CustomBuildCache> {
                @Override BuildCacheService createBuildCacheService(CustomBuildCache configuration, Describer describer) { 
                    new NoOpBuildCacheService() 
                }
            }
            
            buildCache {
                registerBuildCacheService(CustomBuildCache, CustomBuildCacheFactory)
                
                remote(CustomBuildCache)
            }            
        """
        expect:
        succeeds("help", "--build-cache", "--offline", "--info")
        outputContains("Remote build cache is disabled when running with --offline.")
    }

    def "unregistered build cache type is reported even when disabled"() {
        settingsFile << """
            class CustomBuildCache extends AbstractBuildCache {}
            
            buildCache {
                remote(CustomBuildCache) {
                    enabled = false
                }
            }            
        """
        expect:
        fails("help")
        failureHasCause("Build cache type 'CustomBuildCache' has not been registered.")
    }

    def "emits a useful message when using the build cache"() {
        when:
        executer.withBuildCacheEnabled()
        succeeds("tasks", "--info")
        then:
        outputContains("Using local directory build cache")
    }

    def "command-line --no-build-cache wins over system property"() {
        file("gradle.properties") << """
            org.gradle.caching=true
        """
        executer.withArgument("--no-build-cache")
        when:
        succeeds("tasks", "--info")
        then:
        outputDoesNotContain("Using local directory build cache")
    }

    def "command-line --build-cache wins over system property"() {
        file("gradle.properties") << """
            org.gradle.caching=false
        """
        executer.withArgument("--build-cache")
        when:
        succeeds("tasks", "--info")
        then:
        outputContains("Using local directory build cache")
    }

    def "does not use the build cache when it is not enabled"() {
        given:
        buildFile << customTaskCode()
        when:
        // Disable the local build cache
        settingsFile << """
            buildCache {
                local(DirectoryBuildCache) {
                    directory = '$cacheDir'
                    enabled = false
                }
            }
        """
        executer.withBuildCacheEnabled()
        succeeds("customTask")
        then:
        outputContains("Task output caching is enabled, but no build caches are configured or enabled.")

        and:
        localBuildCache.empty
    }

    def "does not populate the build cache when we cannot push to it"() {
        given:
        buildFile << customTaskCode()
        when:
        // Disable pushing to the local build cache
        settingsFile << """
            buildCache {
                local(DirectoryBuildCache) {
                    directory = file("local-cache")
                    push = false
                }
            }
        """
        executer.withBuildCacheEnabled()
        succeeds("customTask", "--info")

        then:
        outputContains("Using local directory build cache for the root build (pull-only, location = ${file("local-cache")}, removeUnusedEntriesAfter = 7 days).")

        and:
        localBuildCache.empty
    }

    @Unroll
    def "shows deprecation warning when using custom local cache using #customCacheConfig"() {
        settingsFile << """
            class CustomBuildCache extends AbstractBuildCache {}
            class CustomBuildCacheFactory implements BuildCacheServiceFactory<CustomBuildCache> {
                @Override BuildCacheService createBuildCacheService(CustomBuildCache configuration, Describer describer) { 
                    throw new UnsupportedOperationException() 
                }
            }
            
            buildCache {
                registerBuildCacheService(CustomBuildCache, CustomBuildCacheFactory)
                
                $customCacheConfig
            }
        """
        executer.expectDeprecationWarning()

        when:
        run "help"

        then:
        output.contains("Using a local build cache type other than DirectoryBuildCache has been deprecated. This is scheduled to be removed in Gradle 6.0.")

        where:
        customCacheConfig << [
            "local(CustomBuildCache).enabled = false",
            "local(CustomBuildCache) { enabled = false }"
        ]
    }

    private static String customTaskCode() {
        """
            @CacheableTask
            class CustomTask extends DefaultTask {
                @OutputFile
                File outputFile = new File(temporaryDir, "output.txt")

                @TaskAction
                void generate() {
                    outputFile.text = "done"
                }
            }

            task customTask(type: CustomTask)
        """
    }

}

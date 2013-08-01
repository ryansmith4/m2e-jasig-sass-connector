/**
 * 
 */
package org.eclipselabs.m2e.jasig.sass.connector;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.codehaus.plexus.util.Scanner;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.embedder.IMaven;
import org.eclipse.m2e.core.project.configurator.MojoExecutionBuildParticipant;
import org.sonatype.plexus.build.incremental.BuildContext;

public class JasigSassBuildParticipant extends MojoExecutionBuildParticipant
{

	public JasigSassBuildParticipant( MojoExecution execution )
	{
		super( execution, true );
	}

	private static boolean pathExists( File path )
	{
		return path != null && path.exists();
	}

	private static <T> boolean isEmpty( final T[] array )
	{
		return array == null || array.length == 0;
	}

	private static <T> boolean isEmpty( final Collection<T> col )
	{
		return col == null || col.isEmpty();
	}

	@Override
	public Set<IProject> build( int kind, IProgressMonitor monitor ) throws Exception
	{
		final IMaven maven = MavenPlugin.getMaven();
		final BuildContext buildContext = getBuildContext();
		final MavenSession mavenSession = getSession();
		final MojoExecution mojoExecution = getMojoExecution();

		boolean filesModified = false;

		File buildDirectory = maven.getMojoParameterValue( mavenSession, mojoExecution, "buildDirectory", File.class );
		File destinationDirectory = maven.getMojoParameterValue( mavenSession, mojoExecution, "destination", File.class );

		if ( !pathExists( buildDirectory ) || !pathExists( destinationDirectory ) )
		{
			// If either the buildDirectory or destinationDirectory are
			// non-existent, assume the project needs to be built
			filesModified = true;
		}
		else
		{
			List<?> resources = maven.getMojoParameterValue( mavenSession, mojoExecution, "resources", List.class );
			if ( !isEmpty( resources ) )
			{
				Iterator<?> resourceIter = resources.iterator();
				while ( resourceIter.hasNext() && !filesModified )
				{
					Object resource = resourceIter.next();
					Class<? extends Object> k = resource.getClass();

					// Invoke the getDirectoriesAndDestinations method by reflection in order avoid a direct
					// dependency on a particular Jasig Sass plugin version
					Method getDirectoriesAndDestinations = k.getMethod( "getDirectoriesAndDestinations" );
					Object result = getDirectoriesAndDestinations.invoke( resource );
					if ( result == null )
					{
						continue;
					}

					// Basic validation to protect against future ClassCastExceptions if the return type should ever change
					// This won't protect against the return result being a Map but not matching Map<String, String>,
					// but its better than nothing
					if ( result instanceof Map )
					{
						@SuppressWarnings( "unchecked" )
						Map<String, String> map = (Map<String, String>) result;
						for ( Map.Entry<String, String> me : map.entrySet() )
						{
							String destDir = me.getValue();
							if ( destDir != null && !pathExists( new File( destDir ) ) )
							{
								// If the destination directory doesn't exist, assume the project needs to be built
								// Don't bother processing the remaining entries, since we know a build needs to be done
								filesModified = true;
								break;
							}

							String srcDir = me.getKey();
							if ( srcDir == null || !pathExists( new File( srcDir ) ) )
							{
								// Abort processing since the specified srcDir doesn't exist
								String errMsg = String.format( "Specified resource srcDir (%s) does not exist", srcDir );
								buildContext.addMessage( null, -1, -1, errMsg, BuildContext.SEVERITY_ERROR, null );
								return null;
							}

							Scanner scanner = buildContext.newScanner( new File( srcDir ) );
							scanner.scan();

							String[] includedFiles = scanner.getIncludedFiles();
							if ( !isEmpty( includedFiles ) )
							{
								// Don't bother processing the remaining entries, since we know a build needs to be done
								filesModified = true;
								break;
							}
						}
					}
					else
					{
						// The return type was not expected, assume files have been modified until the connector can be patched
						buildContext.addMessage( null, -1, -1, "Failed to process resource directories, assuming files modified",
								BuildContext.SEVERITY_WARNING, null );
						filesModified = true;
					}
				}
			}
			else
			{
				File sassSourceDirectory = maven.getMojoParameterValue( mavenSession, mojoExecution, "sassSourceDirectory", File.class );
				if ( !pathExists( sassSourceDirectory ) )
				{
					// Abort processing if the sassSourceDirectory cannot be found as it is required
					String errMsg = String.format( "Specified sassSourceDirectory (%s) does not exist", sassSourceDirectory );
					buildContext.addMessage( sassSourceDirectory, -1, -1, errMsg, BuildContext.SEVERITY_ERROR, null );
					return null;
				}

				Scanner scanner = buildContext.newScanner( sassSourceDirectory );
				String[] includes = maven.getMojoParameterValue( mavenSession, mojoExecution, "includes", String[].class );
				if ( !isEmpty( includes ) )
				{
					// Configure the scanner with any provided includes
					scanner.setIncludes( includes );
				}
				String[] excludes = maven.getMojoParameterValue( mavenSession, mojoExecution, "excludes", String[].class );
				if ( !isEmpty( excludes ) )
				{
					// Configure the scanner with any provided excludes
					scanner.setExcludes( excludes );
				}
				scanner.scan();

				String[] includedFiles = scanner.getIncludedFiles();
				if ( !isEmpty( includedFiles ) )
				{
					filesModified = true;
				}
			}
		}

		if ( !filesModified )
		{
			// As no files were found to be modified, abort performing a build/refresh
			return null;
		}

		final Set<IProject> result = super.build( kind, monitor );

		if ( destinationDirectory != null )
		{
			buildContext.refresh( destinationDirectory );
		}

		return result;
	}
}

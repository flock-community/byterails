package community.flock.byterails.maven;

import community.flock.byterails.ByterailsRunner;
import community.flock.byterails.model.ConfigException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.util.List;

/**
 * Checks the compiled classes of the module against {@code byterails.kts}.
 *
 * <p>Bound to {@code verify} by default. Every module of a multi-module build checks its own
 * classes against the rules file in the multi-module root, so nothing needs to be aggregated.
 */
@Mojo(name = "check", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class CheckMojo extends AbstractMojo {

    /** The rules file. Defaults to {@code byterails.kts} in the multi-module root directory. */
    @Parameter(property = "byterails.rulesFile", defaultValue = "${maven.multiModuleProjectDirectory}/byterails.kts")
    private File rulesFile;

    /** The compiled main classes of this module. */
    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private File classesDirectory;

    /**
     * A package every declaration in the rules file is relative to, for example {@code com.acme}.
     * A rule prefix is prefixed too when it points into the declared package tree.
     */
    @Parameter(property = "byterails.basePackage")
    private String basePackage;

    /**
     * The slices of the application: package names relative to the base package. The rules file's
     * {@code slice { }} block is applied to each of them. As a property: {@code -Dbyterails.slices=orders,customers}.
     */
    @Parameter(property = "byterails.slices")
    private List<String> slices;

    /**
     * Rule sets byterails ships, by id, applied on top of the rules file or instead of it:
     * {@code hexagonal} keeps a {@code domain} package free of external dependencies.
     */
    @Parameter(property = "byterails.defaultRules")
    private List<String> defaultRules;

    /** When true, violations are printed and the build stays green. */
    @Parameter(property = "byterails.reportOnly", defaultValue = "false")
    private boolean reportOnly;

    /** Skips the check entirely. */
    @Parameter(property = "byterails.skip", defaultValue = "false")
    private boolean skip;

    /** Where the JSON report is written. */
    @Parameter(defaultValue = "${project.build.directory}/byterails/violations.json")
    private File reportFile;

    /** Where compiled rules files are cached by content hash. */
    @Parameter(defaultValue = "${project.build.directory}/byterails/script-cache")
    private File scriptCacheDir;

    @Parameter(defaultValue = "${project.packaging}", readonly = true)
    private String packaging;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("byterails: skipped");
            return;
        }
        if ("pom".equals(packaging)) {
            getLog().debug("byterails: nothing to check in a pom module");
            return;
        }
        if (!classesDirectory.isDirectory()) {
            getLog().info("byterails: no compiled classes in " + classesDirectory + ", nothing to check");
            return;
        }
        boolean hasDefaults = defaultRules != null && !defaultRules.isEmpty();
        if (!rulesFile.isFile() && !hasDefaults) {
            throw new MojoFailureException("byterails: rules file " + rulesFile + " does not exist and no defaultRules are set");
        }
        File rules = rulesFile.isFile() ? rulesFile : null;

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(CheckMojo.class.getClassLoader());
        int violations;
        try {
            violations = ByterailsRunner.run(
                    rules, List.of(classesDirectory), reportFile, scriptCacheDir, basePackage, slices, defaultRules, line -> getLog().info(line));
        } catch (ConfigException e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new MojoExecutionException("byterails failed: " + e, e);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }

        if (violations > 0 && !reportOnly) {
            String noun = violations == 1 ? "violation" : "violations";
            throw new MojoFailureException("byterails found " + violations + " " + noun + "; see the lines above or " + reportFile);
        }
    }
}

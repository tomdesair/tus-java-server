# Release Process for tus-java-server

This document outlines the steps required to publish a new release of `tus-java-server` to Maven Central.

## Prerequisites

To perform a release, ensure you have the following installed and configured locally:

1. **Java 17+**: Ensure the JDK is installed and the `JAVA_HOME` environment variable is set.
2. **Maven (`mvn`)**: Ensure Apache Maven is installed (3.3.x or higher).
3. **Sonatype Jira Account**: You must have a Sonatype account and be authorized to deploy to the `me.desair.tus` group ID on Maven Central.
4. **GitHub Access**: Write access to the [tomdesair/tus-java-server](https://github.com/tomdesair/tus-java-server) repository.
5. **GPG Key**: A personal GPG code signing key generated, published to a key server (e.g., `hkps://keyserver.ubuntu.com`), and configured locally. More info can be found here: https://central.sonatype.org/publish/requirements/gpg/
6. **Maven Settings (`settings.xml`)**:
   Generate your Sonatype Central Portal Token via the instructions at https://central.sonatype.org/publish/generate-portal-token/
   Then configure your Sonatype credentials and GPG profile in your `~/.m2/settings.xml` file:

   ```xml
   <settings>
     <servers>
       <server>
         <id>central</id>
         <username>YourSonatypeTokenUsername</username>
         <password>YourSonatypeTokenPassword</password>
       </server>
     </servers>
     <profiles>
       <profile>
         <id>gpg-profile</id>
         <properties>
           <gpg.useagent>true</gpg.useagent>
           <!-- If you have gpg2 instead, you can tell Maven to use it instead of 'gpg' by uncommenting the following -->
           <!--<gpg.executable>gpg2</gpg.executable>-->
         </properties>
       </profile>
     </profiles>
   </settings>
   ```

---

## Stages of the Release

Below are the step-by-step instructions to release a new version.

### 1. Verification and Snapshot Deployment (Optional but Recommended)

Before cutting a release, make sure the project builds properly and that you can successfully deploy a SNAPSHOT version.

```bash
export GPG_TTY=$(tty)
mvn clean install
mvn clean deploy -P release
```

- **`mvn clean install`**: Compiles the project, runs all unit and integration tests, and installs the snapshot into your local Maven repository.
- **`mvn clean deploy -P release`**: Deploys the current SNAPSHOT version to the Sonatype snapshot repository. Using `-P release` ensures that the Javadoc JARs, Source JARs, and GPG signatures are generated and tested before the real release.

### 2. Dry Run the Release

Always perform a dry run first to verify that the versions will be bumped correctly and that there are no issues with the POM structure.

```bash
export GPG_TTY=$(tty)
mvn release:clean
mvn release:prepare -DdryRun=true -Dresume=false -P release
```

- **`mvn release:clean`**: Cleans up any leftover release files (e.g., `release.properties`, backup POMs) from previous interrupted runs.
- **`mvn release:prepare -DdryRun=true`**: Simulates the release process. You will be prompted to enter:
  - The release version (e.g., `1.0.0`)
  - The SCM tag name (e.g., `tus-java-server-1.0.0` or `1.0.0`)
  - The next development version (e.g., `1.1.0-SNAPSHOT`)

*No changes are committed to Git during a dry run.*

### 3. Prepare the Actual Release

If the dry run was successful and the generated POM files (`pom.xml.tag`) look correct, clean up the dry run files and proceed with the actual prepare phase.

```bash
mvn release:clean
mvn release:prepare -Dresume=false -P release
```

> **Warning:** You *must* run `mvn release:clean` to remove the dry run files before running `release:prepare` for real!

- **`mvn release:prepare -Dresume=false -P release`**: This step will:
  1. Remove `-SNAPSHOT` from the version in `pom.xml`.
  2. Commit the modified POM.
  3. Tag the code in Git with the specified release tag.
  4. Bump the version to the next development snapshot (e.g., `1.1.0-SNAPSHOT`).
  5. Commit the new development POM.
  6. Push the commits and the tag to the remote GitHub repository.

*(Note: Ensure your local `master` branch is up to date with `git pull` before running this, so you don't run into push conflicts).*

### 4. Perform the Release

Once the release is prepared and tagged in Git, build the tagged release and upload the artifacts to the Sonatype staging repository.

```bash
mvn release:perform -P release
```

- **`mvn release:perform -P release`**: This command checks out the newly pushed release tag into the `target/checkout` directory, builds the project, generates Javadocs and Source JARs, signs the artifacts with GPG, and deploys them to the Maven Central Staging repository (`central`).

### 5. Final Cleanup

After the release is successfully deployed to staging, clean up the temporary release files in your working directory.

```bash
mvn release:clean
```

### 6. Verify and Release on Sonatype Nexus

Finally, log into Sonatype Nexus to verify and officially publish the staged artifacts to Maven Central:

1. Go to [https://central.sonatype.com/publishing/deployments](https://central.sonatype.com/publishing/deployments)
3. Find the deployment based on the ID that was printed during the release process. It should have status `Validated`.
4. Open the `Component Files` section. Verify the POM versions and the presence of `.jar`, `-javadoc.jar`, `-sources.jar`, and their `.asc` signatures.
5. If everything looks correct, click the **Publish** button in the top menu to publish the release to world.
6. The status of the deployment will go to `Publishing` and it will take a few minutes before it completes.


6. Once successfully closed, click the **Release** button to publish the artifacts to Maven Central. *(Note: It may take a few hours for the artifacts to sync and appear on search.maven.org).*
7. If anything went wrong during your verification, click **Drop** instead, fix the issue in the codebase, and restart the release process.

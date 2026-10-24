# Third-Party Notices

CarbonGuard itself is licensed under **GPL-3.0-only**. See [LICENSE](LICENSE).

The following components are included in the shaded `CarbonGuard-1.0.0.jar`.
They remain under their respective licenses; nothing in the CarbonGuard license
overrides those terms.

| Component | Version | License | Project |
| --- | --- | --- | --- |
| HikariCP | 7.1.0 | Apache License 2.0 | https://github.com/brettwooldridge/HikariCP |
| SQLite JDBC | 3.53.4.0 | Apache License 2.0 | https://github.com/xerial/sqlite-jdbc |
| Jakarta Mail | 2.0.2 | EPL-2.0 OR GPL-2.0-only with Classpath Exception | https://github.com/eclipse-ee4j/mail |
| Jakarta Activation | 2.0.1 | EPL-2.0 OR GPL-2.0-only with Classpath Exception | https://github.com/eclipse-ee4j/jaf |

The Apache License 2.0 text is included at [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt).
The Eclipse Public License 2.0 text is included at [LICENSES/EPL-2.0.txt](LICENSES/EPL-2.0.txt).
The GNU GPL v3 text is included at [LICENSE](LICENSE).

Jakarta Mail and Jakarta Activation are used under the EPL-2.0 option; the EPL-2.0
text is therefore included. HikariCP and SQLite JDBC are used under Apache-2.0.

## Runtime-provided components (not bundled)

CarbonGuard compiles against the following APIs but does not redistribute them:

- **Paper API 26.2** — provided by the Paper server; https://papermc.io/
- **Floodgate API** — optional; provided by the Floodgate plugin; https://geysermc.org/
- **Geyser API** — optional; provided by the Geyser plugin; https://geysermc.org/
- **SLF4J 2.x, Adventure 5.2, Gson** — provided by Paper 26.2.

Optional integrations are detected defensively at runtime. CarbonGuard does not
bundle Floodgate, Geyser, Paper, Adventure, Gson, or SLF4J classes.

## Test-only components

JUnit Jupiter is used only for tests and is not included in the distributed jar.
JUnit is licensed under the Eclipse Public License 2.0.

## No warranty

CarbonGuard is distributed in the hope that it will be useful, but **WITHOUT ANY
WARRANTY**; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See the GNU General Public License for details.

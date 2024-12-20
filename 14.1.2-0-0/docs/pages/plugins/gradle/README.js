<doc-view>

<v-layout row wrap>
<v-flex xs12 sm10 lg10>
<v-card class="section-def" v-bind:color="$store.state.currentColor">
<v-card-text class="pa-3">
<v-card class="section-def__card">
<v-card-text>
<dl>
<dt slot=title>POF Gradle Plugin</dt>
<dd slot="desc"><p>The POF Gradle Plugin provides automated instrumentation of classes with the <code>@PortableType</code> annotation to generate
consistent (and correct) implementations of Evolvable POF serialization methods.</p>

<p>It is a far from a trivial exercise to manually write serialization methods that support serializing inheritance
hierarchies that support the Evolvable concept. However, with static type analysis these methods can be deterministically
generated.</p>

<p>This allows developers to focus on business logic rather than implementing boilerplate code for the above-mentioned
methods.</p>

<div class="admonition note">
<p class="admonition-inline">Please see
<a id="" title="" target="_blank" href="https://docs.oracle.com/en/middleware/standalone/coherence/14.1.1.2206/develop-applications/using-portable-object-format.html#GUID-25206CEF-3271-494C-B43A-066A84E6B1BD">Portable Types documentation</a>
for more information and detailed instructions on Portable Types creation and usage.</p>
</div></dd>
</dl>
</v-card-text>
</v-card>
</v-card-text>
</v-card>
</v-flex>
</v-layout>

<h2 id="_usage">Usage</h2>
<div class="section">
<p>In order to use the POF Gradle Plugin, you need to declare it as a plugin dependency in your <code>build.gradle</code> file:</p>

<markup
lang="groovy"

>plugins {
    id 'java'
    id 'com.oracle.coherence'
}</markup>

<p>Without any further configuration, the plugin will add a task named <code>coherencePof</code> to your project and you will see the
task listed under the task group <code>Coherence</code> when you execute:</p>

<markup
lang="bash"

>gradle tasks</markup>

<p>The <code>coherencePof</code> task is added as a <code>doLast</code> action to the <code>compileJava</code> task. Therefore, executing:</p>

<markup
lang="bash"

>gradle compileJava</markup>

<p>will automatically execute the <code>coherencePof</code> task. By <strong>default</strong>, the <code>coherencePof</code> task will instrument all Java
classes excluding any test classes. The POF Gradle Plugin supports
<a id="" title="" target="_blank" href="https://docs.gradle.org/current/userguide/incremental_build.html">incremental builds</a>. This means that only if Java classes
have changed, the <code>coherencePof</code> task will execute.</p>

<p>The <code>coherencePof</code> task will also become a dependency to all tasks that depend on the <code>compileJava</code>. Therefore, executing
the <code>build</code> or <code>jar</code> task will invoke the <code>coherencePof</code> task in case of class changes.</p>

<p>By just adding the plugin using the configuration above, the Coherence Gradle Plugin will discover and instrument all
project classes annotated with the <code>@PortableType</code> annotation, excluding test classes. If you do need to instrument test
classes, you can add the <code>coherencePof</code> closure and provide additional configuration properties.</p>


<h3 id="_custom_configuration">Custom Configuration</h3>
<div class="section">
<p>The default behavior of the Coherence Gradle Plugin, can be customized using several optional properties. Simply provide
a <code>coherencePof</code> closure to your <code>build.gradle</code> script containing any additional configuration properties, e.g.:</p>

<markup
lang="groovy"
title="Build.gradle"
>coherencePof {
  debug=true <span class="conum" data-value="1" />
}</markup>

<ul class="colist">
<li data-value="1">This will instruct Coherence to provide more logging output in regard to the instrumented classes</li>
</ul>
</div>

<h3 id="_available_configuration_properties">Available Configuration Properties</h3>
<div class="section">

<h4 id="_enable_debugging">Enable Debugging</h4>
<div class="section">
<p>Set the boolean <code>debug</code> property to <code>true</code> in order to instruct the underlying <code>PortableTypeGenerator</code> to generate debug
code in regards the instrumented classes.</p>

<p>If not specified, this property <em>defaults</em> to <code>false</code>.</p>

</div>

<h4 id="_instrumentation_of_test_classes">Instrumentation of Test Classes</h4>
<div class="section">
<p>Set the boolean <code>instrumentTestClasses</code> property to <code>true</code> in order to instrument test classes.
If not specified, this property <em>defaults</em> to <code>false</code>.</p>

</div>
</div>

<h3 id="_what_about_classes_without_the_portabletype_annotation">What about classes without the @PortableType annotation?</h3>
<div class="section">
<p>In some cases, it may be necessary to expand the type system with the types that are not annotated with the
<code>@PortableType</code> annotation, and are not discovered automatically. This is typically the case when some of your portable
types have <code>enum</code> values, or existing classes that implement the <code>PortableObject</code> interface explicitly as attributes.</p>

<p>You can add those types to the schema by creating a <code>META-INF/schema.xml</code> file and specifying them explicitly. For example,
if you assume that the <code>Color</code> class from the earlier code examples:</p>

<markup
lang="xml"
title="META-INF/schema.xml"
>&lt;?xml version="1.0"?&gt;

&lt;schema xmlns="http://xmlns.oracle.com/coherence/schema"
        xmlns:java="http://xmlns.oracle.com/coherence/schema/java" external="true"&gt;

  &lt;type name="Color"&gt;
    &lt;java:type name="petstore.Color"/&gt;
  &lt;/type&gt;

&lt;/schema&gt;</markup>

<p>In order to use a <code>schema.xml</code> file, you need to set <code>usePofSchemaXml</code> to <code>true</code>. Also, you can customize the location of
the <code>schema.xml</code> by setting <code>pofSchemaXmlPath</code> to a value other than <code>META-INF/schema.xml</code>. For example:</p>

<markup
lang="groovy"
title="Build.gradle"
>coherencePof {
  usePofSchemaXml = true
  pofSchemaXmlPath = 'META-INF/my-schema.xml'
}</markup>

</div>
</div>

<h2 id="_generating_pof_index_files">Generating POF Index Files</h2>
<div class="section">
<p>The portable type discovery feature of Coherence can use index files to speed up the discovery of <code>@PortableType</code> annotated
classes. By default, the Gradle plugin will generate index files under <code>META-INF/pod.idx</code> that contain class names of
<code>@PortableType</code> annotated classes. You can skip the generation of those index files by setting the <code>indexPofClasses</code>
property in your Gradle plugin configuration to <code>false</code>.</p>

</div>

<h2 id="_example">Example</h2>
<div class="section">
<p>An example <code>Person</code> class (below) when processed with the plugin, results in the bytecode shown below.</p>

<markup
lang="java"
title="Person.java"
>@PortableType(id=1000)
public class Person
    {
    public Person()
        {
        }

    public Person(int id, String name, Address address)
        {
        super();
        this.id = id;
        this.name = name;
        this.address = address;
        }

    int id;
    String name;
    Address address;

    // getters and setters omitted for brevity
    }</markup>

<p>Let&#8217;s inspect the generated bytecode:</p>

<markup
lang="bash"

>javap Person.class</markup>

<p>This should yield the following output:</p>

<markup
lang="java"

>public class demo.Person implements com.tangosol.io.pof.PortableObject,com.tangosol.io.pof.EvolvableObject {
  int id;
  java.lang.String name;
  demo.Address address;
  public demo.Person();
  public demo.Person(int, java.lang.String, demo.Address);
  public int getId();
  public void setId(int);
  public java.lang.String getName();
  public void setName(java.lang.String);
  public demo.Address getAddress();
  public void setAddress(demo.Address);
  public java.lang.String toString();
  public int hashCode();
  public boolean equals(java.lang.Object);

  public void readExternal(com.tangosol.io.pof.PofReader) throws java.io.IOException; <span class="conum" data-value="1" />
  public void writeExternal(com.tangosol.io.pof.PofWriter) throws java.io.IOException;
  public com.tangosol.io.Evolvable getEvolvable(int);
  public com.tangosol.io.pof.EvolvableHolder getEvolvableHolder();
}</markup>

<ul class="colist">
<li data-value="1">Additional methods generated by Coherence POF plugin.</li>
</ul>
<p>Additionally, you will see that under the <code>META-INF</code> directory you will have a generated POF index file <code>pof.idx</code>. I will
contain the package and class name of the <code>Person</code> class. This will later during the execution of your application speed
up the POF type discovery process for your <code>@PortableType</code> annotated classes.</p>


<h3 id="_skip_execution">Skip Execution</h3>
<div class="section">
<p>You can skip the execution of the <code>coherencePof</code> task by running the Gradle build using the <code>-x</code> flag, e.g.:</p>

<markup
lang="bash"

>gradle clean build -x coherencePof</markup>

</div>
</div>

<h2 id="_development">Development</h2>
<div class="section">
<p>During development, it is extremely useful to rapidly test the plugin code against separate example projects. For this,
we can use Gradle&#8217;s <a id="" title="" target="_blank" href="https://docs.gradle.org/current/userguide/composite_builds.html">composite build</a> feature. Therefore,
the Coherence POF Gradle Plugin module itself can be easily integrated into a separate <code>sample</code> project for rapid testing
of code changes. From within the sample directory you can execute:</p>

<markup
lang="bash"

>gradle clean compileJava --include-build path/to/plugin</markup>

<p>This will not only build the sample but will also build the plugin and developers can make plugin code changes and see
changes rapidly reflected in the execution of the sample module.</p>

<p>Alternatively, you can build and install the Coherence Gradle plugin to your local Maven repository using:</p>

<markup
lang="bash"

>gradle publishToMavenLocal</markup>

<p>For projects to pick up the local changes ensure the following configuration:</p>

<markup
lang="groovy"
title="build.gradle"
>plugins {
  id 'java'
  id 'com.oracle.coherence' version '14.1.2-0-0-SNAPSHOT'
}

dependencies {
  &#8230;&#8203;
  implementation 'com.oracle.coherence:coherence:14.1.2-0-0-SNAPSHOT'
}

repositories {
  mavenLocal()
  mavenCentral()
}</markup>

<markup
lang="groovy"
title="settings.gradle"
>pluginManagement {
  repositories {
    mavenLocal()
    gradlePluginPortal()
  }
}</markup>


<h3 id="_building_using_a_proxy_server">Building using a Proxy Server</h3>
<div class="section">
<p>When building the Coherence Gradle using a proxy server instead of accessing remote repositories directly, you must
ensure that the proxy configuration is propagated all the way down to the Gradle integration tests as they use the
<a id="" title="" target="_blank" href="https://docs.gradle.org/current/userguide/test_kit.html#sec:functional_testing_with_the_gradle_runner">GradleRunner</a>
(Gradle TestKit).</p>


<h4 id="_using_gradle_directly">Using Gradle directly</h4>
<div class="section">
<p>The easiest way to provide the proxy settings (when building the Coherence Gradle plugin by invoking Gradle directly), is
to add the proxy settings to the <code>gradle.properties</code> file:</p>

<markup
lang="properties"

>systemProp.http.proxyHost=your-proxy-host.com
systemProp.http.proxyPort=80
systemProp.https.proxyHost=your-proxy-host.com
systemProp.https.proxyPort=80
systemProp.https.nonProxyHosts=localhost|127.0.0.1</markup>

</div>

<h4 id="_building_the_project_using_maven">Building the Project using Maven</h4>
<div class="section">
<p>When building the entire Coherence project using Maven, we configure the relevant proxy properties
in <code>tools/maven/settings.xml</code>.</p>

<markup
lang="xml"

>  &lt;properties&gt;
    &lt;gradle.https.proxyHost&gt;your-proxy-host.com&lt;/gradle.https.proxyHost&gt;
    &lt;gradle.https.proxyPort&gt;80&lt;/gradle.https.proxyPort&gt;
  &lt;/properties&gt;</markup>

<p>In the <code>pom.xml</code> of the Coherence Gradle plugin module, the proxy properties are then populated using the <code>gradleProxy</code>
Maven profile which is activated as soon as the property <code>gradle.https.proxyHost</code> is present.</p>

<div class="admonition note">
<p class="admonition-inline">The Gradle integration tests are activated once the Maven profile <code>stage1</code> is explicitly activated, and the build
is executed with the Maven phase <code>verify</code> being triggered.</p>
</div>
</div>
</div>
</div>
</doc-view>

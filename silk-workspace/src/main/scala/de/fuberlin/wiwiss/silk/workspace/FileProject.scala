/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.fuberlin.wiwiss.silk.workspace

import modules.linking.{LinkingCaches, LinkingTask, LinkingConfig, LinkingModule}
import modules.output.{OutputTask, OutputConfig, OutputModule}
import de.fuberlin.wiwiss.silk.workspace.modules.source.{TypesCache, SourceConfig, SourceTask, SourceModule}
import xml.XML
import java.io.File
import de.fuberlin.wiwiss.silk.evaluation.ReferenceLinksReader
import de.fuberlin.wiwiss.silk.datasource.Source
import de.fuberlin.wiwiss.silk.config.{Dataset, LinkSpecification, Prefixes}
import de.fuberlin.wiwiss.silk.util.XMLUtils._
import de.fuberlin.wiwiss.silk.util.FileUtils._
import java.util.logging.{Level, Logger}
import de.fuberlin.wiwiss.silk.util.{Timer, Identifier}
import de.fuberlin.wiwiss.silk.output.Output
import de.fuberlin.wiwiss.silk.runtime.resource.FileResourceManager
import de.fuberlin.wiwiss.silk.workspace.modules.transform.{PathsCache, TransformConfig, TransformTask, TransformModule}
import de.fuberlin.wiwiss.silk.linkagerule.TransformRule

/**
 * Implementation of a project which is stored on the local file system.
 */
class FileProject(file : File) extends Project {

  private implicit val logger = Logger.getLogger(classOf[FileProject].getName)

  override val resourceManager = new FileResourceManager(file + "/resources")

  private var cachedConfig : Option[ProjectConfig] = None

  /**
   * The name of this project
   */
  override val name : Identifier = file.getName

  /**
   * Reads the project configuration.
   */
  override def config = {
    if(cachedConfig.isEmpty) {
      val configFile = file + "/config.xml"

      if(configFile.exists) {
        val configXML = XML.loadFile(configFile)
        val prefixes = Prefixes.fromXML(configXML \ "Prefixes" head)
        cachedConfig = Some(ProjectConfig(prefixes))
      } else {
        cachedConfig = Some(ProjectConfig.default)
      }
    }

    cachedConfig.get
  }

  /**
   * Writes the updated project configuration.
   */
  override def config_=(config : ProjectConfig) {
    val configXMl =
      <ProjectConfig>
      { config.prefixes.toXML }
      </ProjectConfig>

    configXMl.write(file + "/config.xml")

    cachedConfig = Some(config)
  }

  /**
   * The source module, which encapsulates all data sources.
   */
  override val sourceModule = new FileSourceModule(file + "/source")

  /**
   * The linking module, which encapsulates all linking tasks.
   */
  override val linkingModule = new FileLinkingModule(file + "/linking")

  /**
   * The transform module, which encapsulates all linking tasks.
   */
  override val transformModule = new FileTransformModule(file + "/transform")

  /**
   * The output module, which encapsulates all output tasks.
   */
  override val outputModule = new FileOutputModule(file + "/output")

  /**
   * The source module, which encapsulates all data sources.
   */
  class FileSourceModule(file : File) extends SourceModule {
    file.mkdirs()

    @volatile
    private var cachedTasks : Map[Identifier, SourceTask] = null

    override def config = SourceConfig()

    override def config_=(c : SourceConfig) {}

    override def tasks = {
      loadSources()
      cachedTasks.values
    }

    override def update(task : SourceTask) {
      loadSources()
      task.source.toXML.write(file + ("/" + task.name + ".xml"))
      task.cache.toXML.write(file + ("/" + task.name + "_cache.xml"))
      cachedTasks += (task.name -> task)
      logger.info(s"Updated source '${task.name}' in project '$name'")
    }

    override def remove(taskId : Identifier) {
      loadSources()
      (file + ("/" + taskId + ".xml")).delete()
      (file + ("/" + taskId + "_cache.xml")).delete()
      cachedTasks -= taskId
      logger.info(s"Removed source '$taskId' from project '$name'")
    }

    private def loadSources() {
      if(cachedTasks == null) {
        cachedTasks = {
          val files = file.list.toList
          val sourceFiles = files.filter(_.endsWith(".xml")).filter(!_.contains("cache"))
          for(fileName <- sourceFiles) yield loadSource(fileName)
        }.map(task => (task.name, task)).toMap
      }
    }

    private def loadSource(fileName: String) = {
      // Load the source
      val source = Source.load(resourceManager)(file + ("/" + fileName))

      // Load the cache
      val cache = new TypesCache()
      try {
        cache.loadFromXML(XML.loadFile(file + ("/" + source.id + "_cache.xml")))
      } catch {
        case ex : Exception =>
          logger.log(Level.WARNING, "Cache corrupted. Rebuilding Cache.", ex)
          new LinkingCaches()
      }

      SourceTask(FileProject.this, source, cache)
    }
  }

  /**
   * The linking module which encapsulates all linking tasks.
   */
  class FileLinkingModule(file : File) extends LinkingModule {
    @volatile
    private var cachedTasks : Map[Identifier, LinkingTask] = load()

    @volatile
    private var updatedTasks = Map[Identifier, LinkingTask]()

    @volatile
    private var lastUpdateTime = 0L

    WriteThread.start()

    override def config = LinkingConfig()

    override def config_=(c : LinkingConfig) {}

    override def tasks = {
      cachedTasks.values
    }

    override def update(task : LinkingTask) {
      cachedTasks += (task.name -> task)
      updatedTasks += (task.name -> task)
      lastUpdateTime = System.currentTimeMillis

      logger.info("Updated linking task '" + task.name + "' in project '" + name + "'")
    }

    override def remove(taskId : Identifier) {
      (file + ("/" + taskId)).deleteRecursive()

      cachedTasks -= taskId
      updatedTasks -= taskId

      logger.info("Removed linking task '" + taskId + "' from project '" + name + "'")
    }

    private def load() : Map[Identifier, LinkingTask] = {
      file.mkdir()

      val tasks =
        for(fileName <- file.list.toList) yield
        {
          val projectConfig = FileProject.this.config
          val linkSpec = LinkSpecification.load(resourceManager)(projectConfig.prefixes)(file + ("/" + fileName + "/linkSpec.xml"))
          val referenceLinks = ReferenceLinksReader.readReferenceLinks(file + ("/" + fileName + "/alignment.xml"))
          val cache = new LinkingCaches()

          //Load the cache
          try {
            cache.loadFromXML(XML.loadFile(file + ("/" + fileName + "/cache.xml")))
          } catch {
            case ex : Exception =>
              logger.log(Level.WARNING, "Cache corrupted. Rebuilding Cache.", ex)
              new LinkingCaches()
          }

          LinkingTask(FileProject.this, linkSpec, referenceLinks, cache)
        }

      tasks.map(task => (task.name, task)).toMap
    }

    def write() {
      val tasksToWrite = updatedTasks.values.toList
      updatedTasks --= tasksToWrite.map(_.name)

      for(task <- tasksToWrite) Timer("Writing task " + task.name + " to disk") {
        val taskDir = file + ("/" + task.name)
        taskDir.mkdir()

        //Don't use any prefixes
        implicit val prefixes = Prefixes.empty

        task.linkSpec.toXML.write(taskDir+ "/linkSpec.xml")
        task.referenceLinks.toXML.write(taskDir+ "/alignment.xml")
        task.cache.toXML.write(taskDir +  "/cache.xml")
      }
    }

    object WriteThread extends Thread {
      private val interval = 5000L

      override def run() {
        while(true) {
          val time = System.currentTimeMillis - lastUpdateTime

          if(updatedTasks.isEmpty) {
            Thread.sleep(interval)
          }
          else if(time >= interval) {
            try {
              linkingModule.write()
            }
            catch {
              case ex : Exception => logger.log(Level.WARNING, "Error writing linking tasks", ex)
            }
          }
          else {
            Thread.sleep(interval - time)
          }
        }
      }
    }
  }

  /**
   * The transform module, which encapsulates all transform tasks.
   */
  class FileTransformModule(file : File) extends TransformModule {
    @volatile
    private var cachedTasks : Map[Identifier, TransformTask] = load()

    @volatile
    private var updatedTasks = Map[Identifier, TransformTask]()

    @volatile
    private var lastUpdateTime = 0L

    WriteThread.start()

    override def config = TransformConfig()

    override def config_=(c : TransformConfig) {}

    override def tasks = {
      cachedTasks.values
    }

    override def update(task : TransformTask) {
      cachedTasks += (task.name -> task)
      updatedTasks += (task.name -> task)
      lastUpdateTime = System.currentTimeMillis

      logger.info("Updated transform task '" + task.name + "' in project '" + name + "'")
    }

    override def remove(taskId : Identifier) {
      (file + ("/" + taskId)).deleteRecursive()

      cachedTasks -= taskId
      updatedTasks -= taskId

      logger.info("Removed transform task '" + taskId + "' from project '" + name + "'")
    }

    private def load() : Map[Identifier, TransformTask] = {
      file.mkdir()

      val tasks =
        for(fileName <- file.list.toList) yield {
          val projectConfig = FileProject.this.config
          implicit val prefixes = projectConfig.prefixes
          val dataset = Dataset.fromXML(XML.loadFile(file + ("/" + fileName + "/dataset.xml")))
          val rule = TransformRule.load(resourceManager)(projectConfig.prefixes)(file + ("/" + fileName + "/rule.xml"))
          val cache = new PathsCache()

          //Load the cache
          try {
            cache.loadFromXML(XML.loadFile(file + ("/" + fileName + "/cache.xml")))
          } catch {
            case ex : Exception =>
              logger.log(Level.WARNING, "Cache corrupted. Rebuilding Cache.", ex)
              new LinkingCaches()
          }

          TransformTask(FileProject.this, fileName, dataset, rule, cache)
        }

      tasks.map(task => (task.name, task)).toMap
    }

    def write() {
      val tasksToWrite = updatedTasks.values.toList
      updatedTasks --= tasksToWrite.map(_.name)

      for(task <- tasksToWrite) Timer("Writing task " + task.name + " to disk") {
        val taskDir = file + ("/" + task.name)
        taskDir.mkdir()

        //Don't use any prefixes
        implicit val prefixes = Prefixes.empty

        task.dataset.toXML(true).write(taskDir+ "/dataset.xml")
        task.rule.toXML.write(taskDir+ "/rule.xml")
        task.cache.toXML.write(taskDir +  "/cache.xml")
      }
    }

    object WriteThread extends Thread {
      private val interval = 5000L

      override def run() {
        while(true) {
          val time = System.currentTimeMillis - lastUpdateTime

          if(updatedTasks.isEmpty) {
            Thread.sleep(interval)
          }
          else if(time >= interval) {
            try {
              transformModule.write()
            }
            catch {
              case ex : Exception => logger.log(Level.WARNING, "Error writing transform tasks", ex)
            }
          }
          else {
            Thread.sleep(interval - time)
          }
        }
      }
    }
  }
  
   /**
   * The output module.
   */
  class FileOutputModule(file : File) extends OutputModule {
    file.mkdirs()

    def config = OutputConfig()

    def config_=(c: OutputConfig) { }

    override def tasks = synchronized {
      for(fileName <- file.list.toList) yield {
        val output = Output.load(resourceManager)(file + ("/" + fileName))

        OutputTask(output)
      }
    }

    override def update(task : OutputTask) = synchronized {
      task.output.toXML.write(file + ("/" + task.name + ".xml"))
      logger.info("Updated output '" + task.name + "' in project '" + name + "'")
    }

    override def remove(taskId : Identifier) = synchronized {
      (file + ("/" + taskId + ".xml")).deleteRecursive()
      logger.info("Removed output '" + taskId + "' from project '" + name + "'")
    }
  }
}
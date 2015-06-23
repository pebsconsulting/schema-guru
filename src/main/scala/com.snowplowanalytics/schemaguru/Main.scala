/*
 * Copyright (c) 2015 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.schemaguru

// Java
import java.io.File
import java.nio.file.{Files, Paths}

// json4s
import org.json4s._
import org.json4s.jackson.JsonMethods._

// Argot
import org.clapper.argot._
import org.clapper.argot.ArgotConverters._

// This library
import utils._

object Main extends App with FileSystemJsonGetters {
  val parser = new ArgotParser(
    programName = "generated.ProjectSettings.name",
    compactUsage = true,
    preUsage = Some("%s: Version %s. Copyright (c) 2015, %s.".format(
      generated.ProjectSettings.name,
      generated.ProjectSettings.version,
      generated.ProjectSettings.organization)
    )
  )

  val directoryArgument = parser.option[String](List("dir"), "directory", "Directory which contains JSONs to be converted")
  val fileArgument = parser.option[String](List("file"), "file", "Single JSON instance to be converted")
  val outputFileArgument = parser.option[String]("output", "file", "Output file")
  val cardinalityArgument = parser.option[Int](List("enum"), "n", "Cardinality to evaluate enum property")
  val ndjsonFlag = parser.flag[Boolean](List("ndjson"), "Expect ndjson format")
  val schemaByArgument = parser.option[String](List("schema-by"), "JSON Path", "Path of Schema title")
  val outputDirArgument = parser.option[String](List("output-dir"), "directory", "Directory path for multiple Schemas")

  parser.parse(args)

  // Get arguments for JSON Path segmentation and validate it
  val segmentSchema = (schemaByArgument.value, outputDirArgument.value) match {
    case (Some(jsonPath), Some(dirPath)) => Some((jsonPath, dirPath))
    case (None, None)                    => None
    case _                               => parser.usage("--schema-by and --output-dir arguments need to be used in conjunction")
  }

  val enumCardinality = cardinalityArgument.value.getOrElse(0)

  // Check whether provided path exists
  List(directoryArgument.value, fileArgument.value).flatten.headOption match {
    case None => parser.usage("either --dir or --file argument must be provided")
    case Some(path) => {
      if (Files.exists(Paths.get(path))) ()  // everything is OK
      else parser.usage(s"Path $path does exists")
    }
  }

  // Decide where and which files should be parsed
  val jsonList: ValidJsonList = directoryArgument.value match {
    case Some(dir) => ndjsonFlag.value match {
      case Some(true) => getJsonsFromFolderWithNDFiles(dir)
      case _          => getJsonsFromFolder(dir)
    }
    case None      => fileArgument.value match {
      case None       => parser.usage("either --dir or --file argument must be provided")
      case Some(file) => ndjsonFlag.value match {
        case Some(true) => getJsonFromNDFile(file)
        case _          => List(getJsonFromFile(file))
      }
    }
  }

  jsonList match {
    case Nil => parser.usage("Directory does not contain any JSON files.")
    case someJsons => {
      segmentSchema match {
        case None => {
          val result = SchemaGuru.convertsJsonsToSchema(someJsons, enumCardinality)
          outputResult(result, outputFileArgument.value)
        }
        case Some((path, dir)) => {
          val nameToJsonsMapping = JsonPathExtractor.mapByPath(path, jsonList)
          nameToJsonsMapping map {
            case (key, jsons) => {
              val result = SchemaGuru.convertsJsonsToSchema(jsons, enumCardinality)
              val fileName = key + ".json"
              val file =
                if (key == "$SchemaGuruFailed") None
                else Some(new File(dir, fileName).getAbsolutePath)
              outputResult(result, file)
            }
          }
        }
      }
    }
  }

  /**
   * Prints Schema, warnings and errors
   * @param result Schema Guru result containing all information
   */
  def outputResult(result: SchemaGuruResult, outputFile: Option[String]): Unit = {
    // Print JsonSchema to file or stdout
    outputFile match {
      case Some(file) => {
        val output = new java.io.PrintWriter(file)
        output.write(pretty(render(result.schema)))
        output.close()
      }
      case None       => println(pretty(render(result.schema)))
    }

    // Print errors
    if (!result.errors.isEmpty) {
      println("\nErrors:\n " + result.errors.mkString("\n"))
    }

    // Print warnings
    result.warning match {
      case Some(warning) => println(warning.consoleMessage)
      case _ =>
    }
  }
}

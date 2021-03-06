/*
 * #%L
 * wcm.io
 * %%
 * Copyright (C) 2015 wcm.io
 * %%
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
 * #L%
 */
package io.wcm.tooling.commons.contentpackagebuilder;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Document;

import com.google.common.base.Charsets;

/**
 * Represents an AEM content package.
 * Content like structured JCR data and binary files can be added.
 * This class is not thread-safe.
 */
public final class ContentPackage implements Closeable {

  private final PackageMetadata metadata;
  private final ZipOutputStream zip;
  private final TransformerFactory transformerFactory;
  private final Transformer transformer;
  private final XmlContentBuilder xmlContentBuilder;

  private static final String CONTENT_TYPE_CHARSET_EXTENSION = ";charset=";

  /**
   * @param os Output stream
   * @throws IOException
   */
  ContentPackage(PackageMetadata metadata, OutputStream os) throws IOException {
    this.metadata = metadata;
    this.zip = new ZipOutputStream(os);

    this.transformerFactory = TransformerFactory.newInstance();
    this.transformerFactory.setAttribute("indent-number", 2);
    try {
      this.transformer = transformerFactory.newTransformer();
      this.transformer.setOutputProperty(OutputKeys.INDENT, "yes");
      this.transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
    }
    catch (TransformerException ex) {
      throw new RuntimeException("Failed to set up XML transformer: " + ex.getMessage(), ex);
    }

    this.xmlContentBuilder = new XmlContentBuilder(metadata.getXmlNamespaces());

    buildPackageMetadata();
  }

  /**
   * Adds a page with given content. The "cq:Page/cq:PageContent envelope" is added automatically.
   * @param path Full content path of page.
   * @param content Map with page properties. If the map contains nested maps this builds a tree of JCR nodes.
   *          The key of the nested map in its parent map is the node name,
   *          the nested map contain the properties of the child node.
   * @throws IOException
   */
  public void addPage(String path, Map<String, Object> content) throws IOException {
    String fullPath = "jcr_root" + path + "/.content.xml";
    Document doc = xmlContentBuilder.buildPage(content);
    writeXmlDocument(fullPath, doc);
  }

  /**
   * Add some JCR content structure directly to the package.
   * @param path Full content path of content root node.
   * @param content Map with node properties. If the map contains nested maps this builds a tree of JCR nodes.
   *          The key of the nested map in its parent map is the node name,
   *          the nested map contain the properties of the child node.
   * @throws IOException
   */
  public void addContent(String path, Map<String, Object> content) throws IOException {
    String fullPath = "jcr_root" + path + "/.content.xml";
    Document doc = xmlContentBuilder.buildContent(content);
    writeXmlDocument(fullPath, doc);
  }

  /**
   * Adds a binary file.
   * @param path Full content path and file name of file
   * @param inputStream Input stream with binary dta
   * @throws IOException
   */
  public void addFile(String path, InputStream inputStream) throws IOException {
    addFile(path, inputStream, null);
  }

  /**
   * Adds a binary file with explicit mime type.
   * @param path Full content path and file name of file
   * @param inputStream Input stream with binary data
   * @param contentType Mime type, optionally with ";charset=XYZ" extension
   * @throws IOException
   */
  public void addFile(String path, InputStream inputStream, String contentType) throws IOException {
    String fullPath = "jcr_root" + path;
    writeBinaryFile(fullPath, inputStream);

    if (StringUtils.isNotEmpty(contentType)) {
      String mimeType = StringUtils.substringBefore(contentType, CONTENT_TYPE_CHARSET_EXTENSION);
      String encoding = StringUtils.substringAfter(contentType, CONTENT_TYPE_CHARSET_EXTENSION);

      String fullPathMetadata = fullPath + ".dir/.content.xml";
      Document doc = xmlContentBuilder.buildNtFile(mimeType, encoding);
      writeXmlDocument(fullPathMetadata, doc);
    }
  }

  /**
   * Adds a binary file.
   * @param path Full content path and file name of file
   * @param file File with binary data
   * @throws IOException
   */
  public void addFile(String path, File file) throws IOException {
    addFile(path, file, null);
  }

  /**
   * Adds a binary file with explicit mime type.
   * @param path Full content path and file name of file
   * @param file File with binary data
   * @param contentType Mime type, optionally with ";charset=XYZ" extension
   * @throws IOException
   */
  public void addFile(String path, File file, String contentType) throws IOException {
    try (InputStream is = new FileInputStream(file)) {
      addFile(path, is, contentType);
    }
  }

  /**
   * Close the underlying ZIP stream of the package.
   * @throws IOException
   */
  @Override
  public void close() throws IOException {
    zip.flush();
    zip.close();
  }

  /**
   * Get root path of the package. This does only work if there is only one filter of the package.
   * If they are more filters use {@link #getFilters()} instead.
   * @return Root path of package
   */
  public String getRootPath() {
    if (metadata.getFilters().size() == 1) {
      return metadata.getFilters().get(0).getRootPath();
    }
    else {
      throw new IllegalStateException("Content package has more than one package filter - please use getFilters().");
    }
  }

  /**
   * Get filters defined for this package.
   * @return List of package filters, optionally with include/exclude rules.
   */
  public List<PackageFilter> getFilters() {
    return metadata.getFilters();
  }

  /**
   * Build all package metadata files based on templates.
   * @throws IOException
   */
  private void buildPackageMetadata() throws IOException {
    metadata.validate();
    buildTemplatedMetadataFile("META-INF/vault/config.xml");
    buildTemplatedMetadataFile("META-INF/vault/properties.xml");
    buildTemplatedMetadataFile("META-INF/vault/settings.xml");
    writeXmlDocument("META-INF/vault/filter.xml", xmlContentBuilder.buildFilter(metadata.getFilters()));
  }

  /**
   * Read template file from classpath, replace variables and store it in the zip stream.
   * @param path Path
   * @throws IOException
   */
  private void buildTemplatedMetadataFile(String path) throws IOException {
    try (InputStream is = getClass().getResourceAsStream("/content-package-template/" + path)) {
      String xmlContent = IOUtils.toString(is);
      for (Map.Entry<String, Object> entry : metadata.getVars(xmlContentBuilder.getJcrTimestampFormat()).entrySet()) {
        xmlContent = StringUtils.replace(xmlContent, "{{" + entry.getKey() + "}}",
            StringEscapeUtils.escapeXml(entry.getValue().toString()));
      }
      zip.putNextEntry(new ZipEntry(path));
      try {
        zip.write(xmlContent.getBytes(Charsets.UTF_8));
      }
      finally {
        zip.closeEntry();
      }
    }
  }

  /**
   * Writes an XML document as binary file entry to the ZIP output stream.
   * @param path Content path
   * @param doc XML conent
   * @throws IOException
   */
  private void writeXmlDocument(String path, Document doc) throws IOException {
    zip.putNextEntry(new ZipEntry(path));
    try {
      DOMSource source = new DOMSource(doc);
      StreamResult result = new StreamResult(zip);
      transformer.transform(source, result);
    }
    catch (TransformerException ex) {
      throw new IOException("Failed to generate XML: " + ex.getMessage(), ex);
    }
    finally {
      zip.closeEntry();
    }
  }

  /**
   * Writes an binary file entry to the ZIP output stream.
   * @param path Content path
   * @param is Input stream with binary data
   * @throws IOException
   */
  private void writeBinaryFile(String path, InputStream is) throws IOException {
    zip.putNextEntry(new ZipEntry(path));
    try {
      IOUtils.copy(is, zip);
    }
    finally {
      zip.closeEntry();
    }
  }

}

/**
 *  Copyright (C) 2004 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.resources;

import orbeon.apache.xml.serialize.OutputFormat;
import orbeon.apache.xml.serialize.XMLSerializer;
import org.apache.log4j.Logger;
import org.dom4j.io.DocumentSource;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.common.ValidationException;
import org.orbeon.oxf.resources.oxf.Handler;
import org.orbeon.oxf.util.LoggerFactory;
import org.orbeon.oxf.xml.ForwardingContentHandler;
import org.orbeon.oxf.xml.TransformerUtils;
import org.orbeon.oxf.xml.XMLUtils;
import org.orbeon.oxf.xml.dom4j.LocationData;
import org.orbeon.oxf.xml.dom4j.LocationSAXContentHandler;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Base class for most resource manager implementations.
 */
public abstract class ResourceManagerBase implements ResourceManager {

    private static Logger logger = LoggerFactory.createLogger(ResourceManagerBase.class);

    private static final String MIN_RELOAD_INTERVAL_KEY = "oxf.resources.common.min-reload-interval";

    private static final long DEFAULT_MIN_RELOAD_INTERVAL = 2 * 1000;
    private static OutputFormat DEFAULT_OUTPUT_FORMAT = new OutputFormat();

    private Map lastCalltoLastModified = Collections.synchronizedMap(new HashMap());
    private long minReloadInterval = DEFAULT_MIN_RELOAD_INTERVAL;

    /**
     * Initialisation. Should be called only by sub-classes
     */
    protected ResourceManagerBase(Map props) {
        // Override default reload interval if property is specified
        String minReloadIntervalString = (String) props.get(MIN_RELOAD_INTERVAL_KEY);
        if (minReloadIntervalString != null) {
            long longValue = Long.parseLong(minReloadIntervalString);
            if (longValue < 0)
                throw new OXFException("Value for property '" + MIN_RELOAD_INTERVAL_KEY + "' must be a non-negative integer.");
            minReloadInterval = longValue;
        }
    }

    /**
     * Gets a W3C DOM node for the specified key. The key must point to an XML
     * document, or a OXFException is raised.
     *
     * @param key A Resource Manager key
     * @return a node element
     */
    public Node getContentAsDOM(String key) {
        try {
            TransformerHandler identity = TransformerUtils.getIdentityTransformerHandler();
            DOMResult domResult = new DOMResult(XMLUtils.createDocument());
            identity.setResult(domResult);
            getContentAsSAX(key, identity);
            return (Document) domResult.getNode();
        } catch (IllegalArgumentException e) {
            throw new OXFException(e);
        }
    }

    /**
     * Gets a DOM4J document for the specified key. The key must point to an XML
     * document, or a OXFException is raised.
     *
     * @param key A Resource Manager key
     * @return  a document element
     */
    public org.dom4j.Document getContentAsDOM4J(String key) {
        LocationSAXContentHandler lch = new LocationSAXContentHandler();
        getContentAsSAX(key, lch);
        return lch.getDocument();
    }

    /**
     * Gets a document form the resource manager and send SAX events to the
     * specified content handler. the key must point to an XML
     * document, or a OXFException is raised.
     * @param key  A Resource Manager key
     * @param handler a The content handler where SAX events are sent
     */
    public void getContentAsSAX(final String key, ContentHandler handler) {
        InputStream inputStream = null;
        final Locator[] locator = new Locator[1];
        try {
            inputStream = getContentAsStream(key);
            XMLUtils.inputStreamToSAX(inputStream, Handler.PROTOCOL + ":" + key, new ForwardingContentHandler(handler) {
                public void setDocumentLocator(Locator loc) {
                    locator[0] = loc;
                    super.setDocumentLocator(loc);
                }
            }, false); // Question: when should we validate?
        } catch (ValidationException ve) {
            throw ve;
        } catch (ResourceNotFoundException rnfe) {
            throw rnfe;
        } catch (Exception e) {
            if(locator[0] != null)
                throw new ValidationException("Can't retrieve or parse document for key " + key, e, new LocationData(locator[0]));
            else
                throw new OXFException("Can't retrieve or parse document for key " + key, e);
        } finally {
            try {
                if (inputStream != null)
                    inputStream.close();
            } catch (IOException e) {
                throw new OXFException(e);
            }
        }
    }

    public class ResourceManagerXMLReader implements XMLReader {
        private ContentHandler ch = new DefaultHandler();
        private DTDHandler dtd = new DefaultHandler();
        private EntityResolver entity = new DefaultHandler();
        private ErrorHandler error = new DefaultHandler();

        public ContentHandler getContentHandler() {
            return ch;
        }

        public DTDHandler getDTDHandler() {
            return dtd;
        }

        public EntityResolver getEntityResolver() {
            return entity;
        }

        public ErrorHandler getErrorHandler() {
            return error;
        }

        public boolean getFeature(String s) throws SAXNotRecognizedException, SAXNotSupportedException {
            return false;
        }

        public Object getProperty(String s) throws SAXNotRecognizedException, SAXNotSupportedException {
            return null;
        }

        public void parse(String key) throws IOException, SAXException {
            getContentAsSAX(key, ch);
        }

        public void parse(InputSource source) throws IOException, SAXException {
            parse(source.getSystemId());
        }

        public void setContentHandler(ContentHandler handler) {
            this.ch = handler;
        }

        public void setDTDHandler(DTDHandler handler) {
            this.dtd = handler;
        }

        public void setEntityResolver(EntityResolver resolver) {
            this.entity = resolver;
        }

        public void setErrorHandler(ErrorHandler handler) {
            this.error = handler;
        }

        public void setFeature(String s, boolean b) throws SAXNotRecognizedException, SAXNotSupportedException {
            throw new SAXNotSupportedException(s);
        }

        public void setProperty(String s, Object o) throws SAXNotRecognizedException, SAXNotSupportedException {
            throw new SAXNotSupportedException(s);
        }
    }

    /**
     * Returns a XMLReader interface to the resource manager. One should then
     * use the <code>setContentHandler()<code> and <code>parse(String key)</code
     * method to get and parse an XML document into SAX events.
     *
     * @return An XML reader
     */
    public XMLReader getXMLReader() {
        return new ResourceManagerXMLReader();
    }

    /**
     * Returns a XMLReader interface to the resource manager. One should then
     * use the <code>setContentHandler()<code> and <code>parse(String key)</code
     * method to get and parse an XML document into SAX events.
     *
     * @return An XML reader
     */
    public XMLReader getXMLReader(ContentHandler handler) {
        ResourceManagerXMLReader reader = new ResourceManagerXMLReader();
        reader.setContentHandler(handler);
        return reader;
    }


    /**
     * Write the specified document in the Resource Manager
     * @param key A Resource Manager key
     * @param node The W3C DOM node to write
     */
    public void writeDOM(String key, Node node) {
        try {
            Writer writer = null;
            try {
                writer = getWriter(key);
                TransformerUtils.getIdentityTransformer().transform(new DOMSource(node), new StreamResult(writer));
            } finally {
                if (writer != null) writer.close();
            }
        } catch (TransformerException e) {
            throw new OXFException(e);
        } catch (IOException e) {
            throw new OXFException(e);
        }
    }

    /**
     * Write the specified document in the Resource Manager
     * @param key A Resource Manager key
     * @param document A DOM4J document
     */
    public void writeDOM4J(String key, org.dom4j.Document document) {
        try {
            Writer writer = null;
            try {
                writer = getWriter(key);
                TransformerUtils.getIdentityTransformer().transform(new DocumentSource(document), new StreamResult(getWriter(key)));
            } finally {
                if (writer != null) writer.close();
            }
        } catch (TransformerException e) {
            throw new OXFException(e);
        } catch (IOException e) {
            throw new OXFException(e);
        }
    }

    /**
     * Returns a ContentHandler that can write to the Resource Manager
     * @param key A Resource Manager key
     * @return A ContentHandler
     */
    public ContentHandler getWriteContentHandler(String key) {
        final Writer writer = getWriter(key);
        return new XMLSerializer(writer, DEFAULT_OUTPUT_FORMAT) {
            public void endDocument()
                    throws SAXException {
                try {
                    super.endDocument();
                    writer.close();
                } catch (IOException e) {
                    throw new OXFException(e);
                }
            }
        };
    }

    final synchronized public long lastModified(String key) {

        LastModifiedStore last = (LastModifiedStore) lastCalltoLastModified.get(key);
        if (last == null || (last.getLastAccess() + minReloadInterval) < System.currentTimeMillis()) {
            try {
                long lastModified = lastModifiedImpl(key);
                lastCalltoLastModified.put(key, new LastModifiedStore(System.currentTimeMillis(), new Long(lastModified)));
                if (logger.isDebugEnabled())
                    logger.debug("lastModified(" + key + ") : " + lastModified);
                return lastModified;
            } catch (ResourceNotFoundException e) {
                lastCalltoLastModified.put(key, new LastModifiedStore(System.currentTimeMillis(), e));
                if (logger.isDebugEnabled())
                    logger.debug("lastModified(" + key + ") : " + e.getMessage());
                throw e;
            }
        } else {
            last.setLastAccess(System.currentTimeMillis());
            if (last.getLastModified() instanceof Long) {
                return ((Long) last.getLastModified()).longValue();
            } else {
                throw (ResourceNotFoundException) last.getLastModified();
            }
        }
    }

    abstract protected long lastModifiedImpl(String key);

    protected void invalidateLastModifiedCache(String key) {
        lastCalltoLastModified.remove(key);
    }

    private static class LastModifiedStore {
        private long lastAccess;
        private Object lastModified;

        public LastModifiedStore(long lastAccess, Object lastModified) {
            this.lastAccess = lastAccess;
            this.lastModified = lastModified;
        }

        public long getLastAccess() {
            return lastAccess;
        }

        public void setLastAccess(long lastAccess) {
            this.lastAccess = lastAccess;
        }

        public Object getLastModified() {
            return lastModified;
        }

        public void setLastModified(Object lastModified) {
            this.lastModified = lastModified;
        }
    }


}

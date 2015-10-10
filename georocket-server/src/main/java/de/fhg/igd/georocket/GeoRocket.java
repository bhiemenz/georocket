package de.fhg.igd.georocket;

import java.io.FileNotFoundException;

import javax.xml.stream.XMLStreamException;

import com.fasterxml.aalto.AsyncByteArrayFeeder;
import com.fasterxml.aalto.AsyncXMLInputFactory;
import com.fasterxml.aalto.AsyncXMLStreamReader;
import com.fasterxml.aalto.stax.InputFactoryImpl;

import de.fhg.igd.georocket.constants.ConfigConstants;
import de.fhg.igd.georocket.input.FirstLevelSplitter;
import de.fhg.igd.georocket.input.Splitter;
import de.fhg.igd.georocket.input.Window;
import de.fhg.igd.georocket.storage.file.ChunkReadStream;
import de.fhg.igd.georocket.storage.file.FileStore;
import de.fhg.igd.georocket.storage.file.Store;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.streams.Pump;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.rx.java.ObservableFuture;
import io.vertx.rx.java.RxHelper;

/**
 * GeoRocket - A high-performance database for geospatial files
 * @author Michel Kraemer
 */
public class GeoRocket extends AbstractVerticle {
  private static Logger log = LoggerFactory.getLogger(GeoRocket.class);
  
  private Store store;
  
  /**
   * Convert an asynchronous result to an HTTP status code
   * @param ar the result to convert
   * @return the HTTP status code
   */
  private static int resultToCode(AsyncResult<?> ar) {
    if (ar.failed()) {
      if (ar.cause() instanceof ReplyException) {
        return ((ReplyException)ar.cause()).failureCode();
      } else if (ar.cause() instanceof IllegalArgumentException) {
        return 400;
      } else if (ar.cause() instanceof FileNotFoundException) {
        return 404;
      }
      return 500;
    }
    return 200;
  }
  
  /**
   * Recursively read XML tokens from a parser and split them
   * @param xmlParser the parser from which to read the XML tokens
   * @param splitter a splitter that will create chunks out of the XML tokens
   * @param handler a result handler that will be called when all available
   * tokens have been read or when an error has occurred
   */
  private void readTokens(AsyncXMLStreamReader<AsyncByteArrayFeeder> xmlParser,
      Splitter splitter, Handler<AsyncResult<Void>> handler) {
    // read next token
    int event;
    try {
      event = xmlParser.next();
    } catch (XMLStreamException e) {
      handler.handle(Future.failedFuture(e));
      return;
    }
    
    if (event == AsyncXMLStreamReader.EVENT_INCOMPLETE) {
      // wait for more input
      handler.handle(Future.succeededFuture());
      return;
    }
    
    // forward token to splitter
    int pos = xmlParser.getLocation().getCharacterOffset();
    String chunk = splitter.onEvent(event, pos);
    if (chunk != null) {
      // splitter has created a chunk. store it.
      store.add(chunk, ar -> {
        if (ar.failed()) {
          handler.handle(Future.failedFuture(ar.cause()));
        } else {
          // go ahead
          readTokens(xmlParser, splitter, handler);
        }
      });
    } else {
      // splitter did not create a chunk. read next one.
      readTokens(xmlParser, splitter, handler);
    }
  }
  
  /**
   * Imports an XML file from the given input stream into the store
   * @param f the XML file to read
   * @param callback will be called when the operation has finished
   */
  private void readStream(ReadStream<Buffer> f, Handler<AsyncResult<Void>> callback) {
    // create asynchronous XML parser
    AsyncXMLInputFactory xmlInputFactory = new InputFactoryImpl();
    AsyncXMLStreamReader<AsyncByteArrayFeeder> xmlParser = xmlInputFactory.createAsyncForByteArray();
    AsyncByteArrayFeeder xmlFeeder = xmlParser.getInputFeeder();
    
    // create new splitter and a window that buffers input until the
    // next chunk is encountered
    Window window = new Window();
    Splitter splitter = new FirstLevelSplitter(window, xmlParser);
    
    // helper function to close the XML parser
    Runnable closeXmlParser = () -> {
      try {
        xmlParser.close();
      } catch (XMLStreamException e) {
        // we don't have to inform the client about this, just log the error.
        log.warn("Could not close XML parser", e);
      }
    };
    
    // close XML parser at the end
    f.endHandler(v -> {
      xmlFeeder.endOfInput();
      closeXmlParser.run();
      callback.handle(Future.succeededFuture());
    });
    
    // handle input data
    f.handler(buf -> {
      f.pause(); // don't feed more input while readTokens() is doing its work
      
      // buffer input in window
      byte[] bytes = buf.getBytes();
      window.append(buf);
      
      // feed XML parser with input
      try {
        xmlFeeder.feedInput(bytes, 0, bytes.length);
      } catch (XMLStreamException e) {
        closeXmlParser.run();
        callback.handle(Future.failedFuture(e));
        return;
      }
      
      // read XML tokens and split file
      readTokens(xmlParser, splitter, ar -> {
        if (ar.failed()) {
          closeXmlParser.run();
          callback.handle(ar);
        } else {
          f.resume(); // continue feeding tokens
        }
      });
    });
  }
  
  /**
   * Handles the HTTP GET request
   * @param context the routing context
   */
  private void onGet(RoutingContext context) {
    HttpServerRequest request = context.request();
    HttpServerResponse response = context.response();
    
    String name = request.getParam("name");
    
    // get chunk from store
    store.get(name, getar -> {
      if (getar.failed()) {
        log.error("Could not get chunk", getar.cause());
        response.setStatusCode(resultToCode(getar)).end(getar.cause().getMessage());
        return;
      }
      
      // write Content-Length
      ChunkReadStream crs = getar.result();
      response.putHeader("Content-Length", String.valueOf(crs.getSize()));
      
      // send chunk to client
      Pump.pump(crs, response).start();
      crs.endHandler(v -> response.end());
    });
  }
  
  /**
   * Handles the HTTP PUT request
   * @param context the routing context
   */
  private void onPut(RoutingContext context) {
    HttpServerRequest request = context.request();
    readStream(request, ar -> {
      if (ar.failed()) {
        request.response()
          .setStatusCode(resultToCode(ar))
          .end("Could not parse XML: " + ar.cause().getMessage());
        ar.cause().printStackTrace();
      } else {
        request.response()
          .setStatusCode(202) // Accepted
          .setStatusMessage("Accepted file - indexing in progress")
          .end();
      }
    });
  }
  
  private ObservableFuture<String> deployVerticle(Class<? extends Verticle> cls) {
    ObservableFuture<String> observable = RxHelper.observableFuture();
    DeploymentOptions options = new DeploymentOptions().setConfig(config());
    vertx.deployVerticle(cls.getName(), options, observable.toHandler());
    return observable;
  }
  
  private ObservableFuture<HttpServer> deployHttpServer() {
    int port = config().getInteger(ConfigConstants.PORT, ConfigConstants.DEFAULT_PORT);
    
    Router router = Router.router(vertx);
    router.get("/db/:name").handler(this::onGet);
    router.put("/db").handler(this::onPut);
    
    HttpServerOptions serverOptions = new HttpServerOptions()
        .setCompressionSupported(true);
    HttpServer server = vertx.createHttpServer(serverOptions);
    
    ObservableFuture<HttpServer> observable = RxHelper.observableFuture();
    server.requestHandler(router::accept).listen(port, observable.toHandler());
    return observable;
  }
  
  @Override
  public void start(Future<Void> startFuture) {
    log.info("Launching GeoRocket ...");
    
    store = new FileStore(vertx);
    
    ObservableFuture<HttpServer> observable = deployHttpServer();
    observable
      .subscribe(server -> {
        startFuture.complete();
      }, err -> {
        startFuture.fail(err);
      });
  }
  
  /**
   * Runs the server
   * @param args the command line arguments
   */
  public static void main(String[] args) {
    Vertx vertx = Vertx.vertx();
    vertx.deployVerticle(GeoRocket.class.getName(), ar -> {
      if (ar.failed()) {
        log.error("Could not deploy GeoRocket");
        ar.cause().printStackTrace();
        System.exit(1);
        return;
      }
    });
  }
}
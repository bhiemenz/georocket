package io.georocket.index.elasticsearch;

import java.util.Map;

import io.georocket.util.HttpException;
import io.georocket.util.RxUtils;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.rx.java.ObservableFuture;
import io.vertx.rx.java.RxHelper;
import io.vertx.rxjava.core.Vertx;
import io.vertx.rxjava.core.buffer.Buffer;
import io.vertx.rxjava.core.http.HttpClient;
import io.vertx.rxjava.core.http.HttpClientRequest;
import rx.Observable;
import rx.Scheduler;

/**
 * An Elasticsearch client using the HTTP API
 * @author Michel Kraemer
 */
public class RemoteElasticsearchClient implements ElasticsearchClient {
  private static Logger log = LoggerFactory.getLogger(RemoteElasticsearchClient.class);
  
  /**
   * The index to query against
   */
  private final String index;
  
  /**
   * The Vert.x instance
   */
  private final Vertx vertx;
  
  /**
   * The HTTP client used to talk to Elasticsearch
   */
  private final HttpClient client;
  
  /**
   * Connect to an Elasticsearch instance
   * @param host the host to connect to
   * @param port the port on which Elasticsearch is listening for HTTP
   * requests (most likely 9200)
   * @param index the index to query against
   * @param vertx a Vert.x instance
   */
  public RemoteElasticsearchClient(String host, int port, String index,
      Vertx vertx) {
    this.index = index;
    this.vertx = vertx;
    
    HttpClientOptions clientOptions = new HttpClientOptions()
        .setDefaultHost(host)
        .setDefaultPort(port)
        .setKeepAlive(true);
    client = vertx.createHttpClient(clientOptions);
  }

  @Override
  public void close() {
    client.close();
  }
  
  @Override
  public Observable<JsonObject> bulkInsert(String type, Map<String, JsonObject> documents) {
    String uri = "/" + index + "/" + type + "/_bulk";
    
    // prepare the whole body now because it's much faster to send
    // it at once instead of using HTTP chunked mode.
    StringBuilder body = new StringBuilder();
    for (Map.Entry<String, JsonObject> e : documents.entrySet()) {
      String id = e.getKey();
      String source = e.getValue().encode();
      JsonObject subject = new JsonObject().put("_id", id);
      body.append("{\"index\":" + subject.encode() + "}\n" + source + "\n");
    }
    
    return performRequestRetry(HttpMethod.POST, uri, body.toString());
  }
  
  @Override
  public Observable<JsonObject> beginScroll(String type, JsonObject query,
      JsonObject postFilter, JsonObject aggregation, int pageSize, String timeout) {
    String uri = "/" + index + "/" + type + "/_search";
    uri += "?scroll=" + timeout;
    
    JsonObject source = new JsonObject();
    source.put("size", pageSize);
    if (query != null) {
      source.put("query", query);
    }
    if (postFilter != null) {
      source.put("post_filter", postFilter);
    }
    if (aggregation != null) {
      source.put("aggs", aggregation);
    }
    
    // sort by doc (fastest way to scroll)
    source.put("sort", new JsonArray().add("_doc"));
    
    return performRequestRetry(HttpMethod.GET, uri, source.encode());
  }
  
  @Override
  public Observable<JsonObject> continueScroll(String scrollId, String timeout) {
    String uri = "/_search/scroll";
    
    JsonObject source = new JsonObject();
    source.put("scroll", timeout);
    source.put("scroll_id", scrollId);
    
    return performRequestRetry(HttpMethod.GET, uri, source.encode());
  }
  
  @Override
  public Observable<JsonObject> bulkDelete(String type, JsonArray ids) {
    String uri = "/" + index + "/" + type + "/_bulk";
    
    // prepare the whole body now because it's much faster to send
    // it at once instead of using HTTP chunked mode.
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < ids.size(); ++i) {
      String id = ids.getString(i);
      JsonObject subject = new JsonObject().put("_id", id);
      body.append("{\"delete\":" + subject.encode() + "}\n");
    }
    
    return performRequestRetry(HttpMethod.POST, uri, body.toString());
  }
  
  @Override
  public Observable<Boolean> indexExists() {
    return exists("/" + index);
  }
  
  @Override
  public Observable<Boolean> typeExists(String type) {
    return exists("/" + index + "/" + type);
  }
  
  /**
   * Check if the given URI exists by sending an empty request
   * @param uri uri to check
   * @return an observable emitting <code>true</code> if the request
   * was successful or <code>false</code> otherwise
   */
  private Observable<Boolean> exists(String uri) {
    return performRequestRetry(HttpMethod.HEAD, uri, null)
      .map(o -> true)
      .onErrorResumeNext(t -> {
        if (t instanceof HttpException && ((HttpException)t).getStatusCode() == 404) {
          return Observable.just(false);
        }
        return Observable.error(t);
      });
  }
  
  @Override
  public Observable<Boolean> createIndex() {
    String uri = "/" + index;
    return performRequestRetry(HttpMethod.PUT, uri, null)
      .map(res -> res.getBoolean("acknowledged", true));
  }
  
  @Override
  public Observable<Boolean> putMapping(String type, JsonObject mapping) {
    String uri = "/" + index + "/_mapping/" + type;
    return performRequestRetry(HttpMethod.PUT, uri, mapping.encode())
      .map(res -> res.getBoolean("acknowledged", true));
  }
  
  @Override
  public Observable<Boolean> isRunning() {
    HttpClientRequest req = client.head("/");
    return performRequest(req, null).map(v -> true).onErrorReturn(t -> false);
  }
  
  /**
   * Perform an HTTP request and if it fails retry it a couple of times
   * @param method the HTTP method
   * @param uri the request URI
   * @param body the body to send in the request (may be null)
   * @return an observable emitting the parsed response body (may be null if no
   * body was received)
   */
  private Observable<JsonObject> performRequestRetry(HttpMethod method,
      String uri, String body) {
    Scheduler scheduler = RxHelper.scheduler((io.vertx.core.Vertx)vertx.getDelegate());
    return Observable.<JsonObject>create(subscriber -> {
      HttpClientRequest req = client.request(method, uri);
      performRequest(req, body).subscribe(subscriber);
    }).retryWhen(errors -> {
      Observable<Throwable> o = errors.flatMap(error -> {
        if (error instanceof HttpException) {
          // immediately forward HTTP errors, don't retry
          return Observable.error(error);
        }
        return Observable.just(error);
      });
      return RxUtils.makeRetry(5, 1000, scheduler, log).call(o);
    }, scheduler);
  }
  
  /**
   * Perform an HTTP request
   * @param req the request to perform
   * @param body the body to send in the request (may be null)
   * @return an observable emitting the parsed response body (may be null if no
   * body was received)
   */
  private Observable<JsonObject> performRequest(HttpClientRequest req,
      String body) {
    ObservableFuture<JsonObject> observable = RxHelper.observableFuture();
    Handler<AsyncResult<JsonObject>> handler = observable.toHandler();
    
    req.exceptionHandler(t -> {
      handler.handle(Future.failedFuture(t));
    });
    
    req.handler(res -> {
      int code = res.statusCode();
      if (code == 200) {
        Buffer buf = Buffer.buffer();
        res.handler(b -> {
          buf.appendBuffer(b);
        });
        res.endHandler(v -> {
          if (buf.length() > 0) {
            handler.handle(Future.succeededFuture(buf.toJsonObject()));
          } else {
            handler.handle(Future.succeededFuture());
          }
        });
      } else {
        handler.handle(Future.failedFuture(new HttpException(code)));
      }
    });
    
    if (body != null) {
      req.setChunked(false);
      req.putHeader("Content-Length", String.valueOf(body.length()));
      req.end(body);
    } else {
      req.end();
    }
    
    return observable;
  }
}

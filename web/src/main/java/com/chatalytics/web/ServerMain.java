package com.chatalytics.web;

import com.chatalytics.compute.db.dao.ChatAlyticsDAOFactory;
import com.chatalytics.core.CommonCLIBuilder;
import com.chatalytics.core.config.ChatAlyticsConfig;
import com.chatalytics.core.json.JsonObjectMapperFactory;
import com.chatalytics.core.util.YamlUtils;
import com.chatalytics.web.resources.EmojisResource;
import com.chatalytics.web.resources.EntitiesResource;
import com.chatalytics.web.resources.EventsResource;
import com.chatalytics.web.resources.MessageSummaryResource;
import com.chatalytics.web.resources.RoomsResource;
import com.chatalytics.web.resources.StatusResource;
import com.chatalytics.web.resources.UsersResource;
import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider;
import com.google.common.collect.Sets;
import com.sun.jersey.spi.container.servlet.ServletContainer;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.storm.shade.com.google.common.io.Resources;
import org.apache.storm.shade.org.eclipse.jetty.servlets.CrossOriginFilter;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.jaxrs.config.BeanConfig;
import io.swagger.jaxrs.listing.ApiListingResource;

import java.util.EnumSet;
import java.util.Set;

import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.websocket.DeploymentException;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpointConfig;
import javax.websocket.server.ServerEndpointConfig.Configurator;
import javax.ws.rs.core.Application;

import static com.google.common.net.HttpHeaders.ACCEPT;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static com.google.common.net.HttpHeaders.ORIGIN;
import static com.google.common.net.HttpHeaders.X_REQUESTED_WITH;
import static org.eclipse.jetty.http.HttpMethod.GET;
import static org.eclipse.jetty.http.HttpMethod.HEAD;
import static org.eclipse.jetty.http.HttpMethod.POST;

/**
 * Entry point to the web resources
 *
 * @author giannis
 *
 */
public class ServerMain extends Application {

    private static final Logger LOG = LoggerFactory.getLogger(ServerMain.class);

    private final ChatAlyticsConfig config;
    private final RealtimeComputeClient realtimeComputeClient;

    public ServerMain(ChatAlyticsConfig config, RealtimeComputeClient realtimeComputeClient) {
        this.config = config;
        this.realtimeComputeClient = realtimeComputeClient;

        // Sets up classpath scanning for Swagger + JAXRS
        // Resources available at localhost/swagger.json
        BeanConfig beanConfig = new BeanConfig();
        beanConfig.setHost("localhost:" + config.webConfig.port);
        beanConfig.setBasePath("/");
        beanConfig.setResourcePackage(EntitiesResource.class.getPackage().toString());
        beanConfig.setScan(true);
    }

    public static void main(String[] args) throws Exception {
        Options opts = CommonCLIBuilder.getCommonOptions();
        CommandLine cli = CommonCLIBuilder.parseOptions(ServerMain.class, args, opts);
        String configName = CommonCLIBuilder.getConfigOption(cli);
        LOG.info("Loading config {}", configName);
        ChatAlyticsConfig config = YamlUtils.readChatAlyticsConfig(configName);

        EventsResource eventResource = new EventsResource();
        RealtimeComputeClient computeClient = new RealtimeComputeClient(config, eventResource);
        ServerMain serverMain = new ServerMain(config, computeClient);

        LOG.info("Starting compute client");
        serverMain.startComputeClient();

        // Start the server
        Server server = new Server(config.webConfig.port);
        ServletContainer servletContainer = new ServletContainer(serverMain);
        ServletHolder servletHolder = new ServletHolder("/*", servletContainer);
        ServletContextHandler context = new ServletContextHandler();

        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setDirectoriesListed(true);
        resourceHandler.setResourceBase(Resources.getResource("public").toString());
        HandlerList handlers = new HandlerList();
        handlers.addHandler(resourceHandler);
        handlers.addHandler(context);

        FilterHolder cors = context.addFilter(CrossOriginFilter.class, "/*",
                                              EnumSet.of(DispatcherType.REQUEST));
        cors.setInitParameter(CrossOriginFilter.ALLOWED_ORIGINS_PARAM, "*");
        cors.setInitParameter(CrossOriginFilter.ACCESS_CONTROL_ALLOW_ORIGIN_HEADER, "*");
        Set<String> methods = Sets.newHashSet(GET.name(), POST.name(), HEAD.name());
        cors.setInitParameter(CrossOriginFilter.ALLOWED_METHODS_PARAM,  String.join(",", methods));
        Set<String> headers = Sets.newHashSet(X_REQUESTED_WITH, CONTENT_TYPE, ACCEPT, ORIGIN);
        cors.setInitParameter(CrossOriginFilter.ALLOWED_HEADERS_PARAM, String.join(",", headers));

        context.addServlet(servletHolder, "/*");
        server.setHandler(handlers);
        setWebSocketEndpoints(context, eventResource);

        addShutdownHook(computeClient);

        LOG.info("Starting web server");
        server.start();
        server.join();
    }

    @Override
    public Set<Object> getSingletons() {
        // specify object mapper
        JacksonJaxbJsonProvider jsonProvider = new JacksonJaxbJsonProvider();
        jsonProvider.setMapper(JsonObjectMapperFactory.createObjectMapper(config.inputType));

        return Sets.newHashSet(new EntitiesResource(config),
                               new EmojisResource(config),
                               new UsersResource(config),
                               new RoomsResource(config),
                               new MessageSummaryResource(config),
                               new StatusResource(),
                               new ApiListingResource(),
                               jsonProvider);
    }

    protected void startComputeClient() {
        if (!realtimeComputeClient.isRunning()) {
            try {
                realtimeComputeClient.startAsync().awaitRunning();
            } catch (Exception e) {
                LOG.error("Unable to connect to RT compute stream. No data will be streamed");
            }
        }
    }

    /**
     *
     * @param context the context to add the web socket endpoints to
     * @param rtEventResource The instance of the websocket endpoint to return
     * @throws DeploymentException
     */
    private static void setWebSocketEndpoints(ServletContextHandler context,
                                              EventsResource rtEventResource)
            throws DeploymentException, ServletException {

        ServerContainer wsContainer = WebSocketServerContainerInitializer.configureContext(context);

        ServerEndpointConfig serverConfig =
                ServerEndpointConfig.Builder
                                    .create(EventsResource.class, EventsResource.RT_EVENT_ENDPOINT)
                                    .configurator(new Configurator() {
                                        @Override
                                        public <T> T getEndpointInstance(Class<T> endpointClass)
                                                throws InstantiationException {
                                            return endpointClass.cast(rtEventResource);
                                        }
                                    }).build();

        wsContainer.addEndpoint(serverConfig);
    }

    /**
     * Closes all open resources
     *
     * @param computeClient The compute client to close
     */
    private static void addShutdownHook(RealtimeComputeClient computeClient) {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                computeClient.stopAsync().awaitTerminated();
                ChatAlyticsDAOFactory.closeEntityManagerFactory();
            }
        });
    }

}

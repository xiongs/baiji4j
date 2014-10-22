package com.ctriposs.baiji.rpc.server;

import com.ctriposs.baiji.exception.BaijiRuntimeException;
import com.ctriposs.baiji.rpc.common.BaijiContract;
import com.ctriposs.baiji.rpc.common.logging.Logger;
import com.ctriposs.baiji.rpc.common.logging.LoggerFactory;
import com.ctriposs.baiji.rpc.common.util.DaemonThreadFactory;
import com.ctriposs.baiji.rpc.server.handler.NotFoundRequestHandler;
import com.ctriposs.baiji.rpc.server.handler.RedirectRequestHandler;
import com.ctriposs.baiji.rpc.server.handler.RequestHandler;
import com.ctriposs.baiji.rpc.server.plugin.Plugin;
import com.ctriposs.baiji.rpc.server.stats.ServiceStats;
import com.ctriposs.baiji.rpc.server.stats.StatsReportJob;
import com.ctriposs.baiji.util.VersionUtils;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Created by yqdong on 2014/9/18.
 */
public class BaijiServiceHost implements ServiceHost {

    private static final Logger _logger = LoggerFactory.getLogger(BaijiServiceHost.class);

    private static final int DEFAULT_STATS_REPORTING_INTERVAL = 30 * 1000;
    private static final String _frameworkVersion;

    private final ServiceStats _serviceStats;
    private final HostConfig _config;
    private final ServiceMetadata _serviceMetadata;
    private final RequestHandler _redirectMetadataHandler;
    private final RequestHandler _fallBackHandler;
    private final ScheduledExecutorService _statsReportService
            = new ScheduledThreadPoolExecutor(1, new DaemonThreadFactory());

    static {
        _frameworkVersion = VersionUtils.getPackageVersion(BaijiServiceHost.class);
    }

    public BaijiServiceHost(Class<?> serviceClass) {
        this(new HostConfig(), serviceClass);
    }

    public BaijiServiceHost(HostConfig config, Class<?> serviceClass) {
        if (config == null) {
            throw new IllegalArgumentException("Missing required config.");
        }

        _serviceMetadata = buildServiceMetadata(serviceClass);
        _serviceStats = new ServiceStats(_serviceMetadata);
        _config = config;

        _redirectMetadataHandler = new RedirectRequestHandler("~/metadata", true);
        _fallBackHandler = new NotFoundRequestHandler();

        validateConfig();
        initializePlugins();

        _statsReportService.scheduleAtFixedRate(new StatsReportJob(_frameworkVersion, _serviceStats),
                DEFAULT_STATS_REPORTING_INTERVAL, DEFAULT_STATS_REPORTING_INTERVAL, TimeUnit.MILLISECONDS);
    }

    @Override
    public HostConfig getConfig() {
        return _config;
    }

    @Override
    public ServiceMetadata getServiceMetaData() {
        return _serviceMetadata;
    }

    @Override
    public ServiceStats getServiceStats() {
        return _serviceStats;
    }

    @Override
    public void processRequest(HttpRequestWrapper request, HttpResponseWrapper response) {
        String requestPath = request.requestPath();
        try {
            if (requestPath == null || requestPath.isEmpty() || requestPath.equals("/")) {
                _redirectMetadataHandler.handle(this, request, response);
                return;
            }

            for (RequestHandler handler : _config.requestHandlers) {
                handler.handle(this, request, response);
                if (response.isResponseSent()) {
                    break;
                }
            }
            if (!response.isResponseSent()) {
                _fallBackHandler.handle(this, request, response);
            }
        } catch (Exception ex) {
            if (_config.exceptionHandler != null) {
                _config.exceptionHandler.handle(this, request, response, ex);
            } else {
                _logger.error("Error occurs when processing request " + requestPath, ex);
                throw new RuntimeException("Error occurs when processing request.", ex);
            }
        } finally {
            try {
                InputStream requestBody = request.requestBody();
                if (requestBody != null) {
                    requestBody.close();
                }
            } catch (IOException e) {
            }
        }
    }

    private ServiceMetadata buildServiceMetadata(Class<?> serviceClass) {
        Class<?> serviceInterface = null;
        BaijiContract contractAnnoataion = null;

        Class<?>[] interfaces = serviceClass.getInterfaces();
        if (interfaces == null || interfaces.length == 0) return null;
        for (Class<?> intf : interfaces) {
            BaijiContract annotation = intf.getAnnotation(BaijiContract.class);
            if (annotation != null) {
                serviceInterface = intf;
                contractAnnoataion = annotation;
                break;
            }
        }

        ServiceMetadata metaData = new ServiceMetadata();
        metaData.setServiceName(contractAnnoataion.serviceName());
        metaData.setServiceNamespace(contractAnnoataion.serviceNamespace());
        metaData.setCodeGeneratorVersion(contractAnnoataion.codeGeneratorVersion());

        // Cache all operation methods
        for (Method intfMethod : serviceInterface.getMethods()) {
            String name = intfMethod.getName();

            // Check to duplicates
            if (metaData.hasOperation(name)) {
                String errMsg = String.format("Duplicated method %s on service interface %s is not allowed", name,
                        serviceInterface);
                _logger.error(errMsg);
                throw new BaijiRuntimeException(errMsg);
            }

            // Create handler
            Method method;
            try {
                method = serviceClass.getMethod(intfMethod.getName(), intfMethod.getParameterTypes());
            } catch (NoSuchMethodException e) {
                continue;
            }
            OperationHandler handler = new OperationHandler(serviceClass, method);
            metaData.registerOperationHandler(method.getName(), handler);
        }
        return metaData;
    }

    private void validateConfig() {
        if (this._config.contentFormatConfig.getDefaultFormatter() == null) {
            // Default formatter must be provided
            String errMsg = "Missing mandatory default content formatter in host config";
            _logger.error(errMsg);
            throw new BaijiRuntimeException(errMsg);
        }
    }

    private void initializePlugins() {
        for (Plugin plugin : _config.plugins) {
            plugin.register(this);
        }
    }
}

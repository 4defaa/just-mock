package com.sdefaa.just.mock.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdefaa.just.mock.agent.config.JustMockAgentConfigLoader;
import com.sdefaa.just.mock.agent.pojo.AgentConfigProperties;
import com.sdefaa.just.mock.agent.server.EmbeddedHttpServer;
import com.sdefaa.just.mock.agent.transformer.MockClassFileTransformer;
import com.sdefaa.just.mock.common.constant.CommonConstant;
import com.sdefaa.just.mock.common.pojo.ApiInfo;
import com.sdefaa.just.mock.common.pojo.ApiRegistryDTO;
import com.sdefaa.just.mock.common.pojo.TargetClass;
import com.sdefaa.just.mock.common.utils.CommonUtils;

import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.InvocationTargetException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * @author Julius Wong
 * <p>
 * 代理agentmain入口类
 * </p>
 * @since 1.0.0
 */
public class MockAgentMain {

    private static final Logger logger = Logger.getLogger(MockAgentMain.class.getName());
    private static final AtomicBoolean LOADED = new AtomicBoolean(false);

    private static final String WHITE_SPACE = " ";

    public static void agentmain(String args, Instrumentation instrumentation) throws IOException, ClassNotFoundException, InvocationTargetException, NoSuchMethodException, InstantiationException, IllegalAccessException {
        if (LOADED.compareAndSet(false, true)) {
            String[] argvs = args.split(WHITE_SPACE);
            int availablePort = CommonUtils.getAvailablePort();
            // 启动内嵌HttpServer，用于心跳检测和注册Mock拦截
            EmbeddedHttpServer server = new EmbeddedHttpServer(availablePort);
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
              try {
                server.start();
              } catch (InterruptedException e) {
                logger.info("interrupted");
                server.stop();
              }
            });
            // 添加虚拟机shutdownHook
            Runtime.getRuntime().addShutdownHook(new Thread(()-> {
              future.cancel(true);
              server.stop();
            }));
            AgentConfigProperties agentConfigProperties = JustMockAgentConfigLoader.INSTANCE.load(argvs[0]).agentConfigProperties();
            Class[] allLoadedClasses = instrumentation.getAllLoadedClasses();
            List<TargetClass> loadedTargetClasses = Arrays.stream(allLoadedClasses)
              .map(CommonUtils::generateTargetClass)
              .filter(Objects::nonNull)
              .collect(Collectors.toList());
            List<ApiInfo> apiInfoList = loadedTargetClasses.stream()
                    .flatMap(CommonUtils::generateApiInfoFromTargetClass)
                    .toList();
            ApiRegistryDTO apiRegistryDTO = new ApiRegistryDTO();
            apiRegistryDTO.setApiInfos(apiInfoList);
            apiRegistryDTO.setPid(argvs[1]);
            apiRegistryDTO.setPort(availablePort);
            //  Upload apiInfoList
            registry(agentConfigProperties.getRegistryUrl(),apiRegistryDTO);
            loadedTargetClasses.forEach(targetClass -> targetClass.getTargetMethods().forEach(targetMethod -> {
            instrumentation.addTransformer(new MockClassFileTransformer(targetClass.getClazz().getName(),targetMethod.getMethod().getName()),true);
            try {
              instrumentation.retransformClasses(targetClass.getClazz());
            } catch (UnmodifiableClassException e) {
              logger.info("unmodifiable class exception:" + targetClass.getClazz().getName());
            }
          }));
        }

    }

    public static void registry(String url, ApiRegistryDTO apiRegistryDTO) throws IOException, ClassNotFoundException, NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
        ObjectMapper objectMapper = new ObjectMapper();
        byte[] bytes = objectMapper.writeValueAsBytes(apiRegistryDTO);
        URL obj = new URL(url);
        HttpURLConnection con = (HttpURLConnection) obj.openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", CommonConstant.APPLICATION_JSON);
        con.setRequestProperty("Content-Length", String.valueOf(bytes.length));
        con.setDoOutput(true);
        try (DataOutputStream wr = new DataOutputStream(con.getOutputStream())) {
            wr.write(bytes);
        }
        int responseCode = con.getResponseCode();
        Map<String,Object> response = objectMapper.readValue(con.getInputStream(), new TypeReference<>() {
        });
        logger.info("register Api, status code:"+responseCode+",message:"+response.toString());
    }

}

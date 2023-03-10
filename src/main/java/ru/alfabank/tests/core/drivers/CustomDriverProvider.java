/*
 * Copyright 2017 Alfa Laboratory
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ru.alfabank.tests.core.drivers;

import com.github.kklisura.cdt.protocol.commands.Fetch;
import com.github.kklisura.cdt.protocol.types.fetch.RequestPattern;
import com.github.kklisura.cdt.protocol.types.network.ErrorReason;
import com.github.kklisura.cdt.services.ChromeDevToolsService;
import com.github.kklisura.cdt.services.WebSocketService;
import com.github.kklisura.cdt.services.config.ChromeDevToolsServiceConfiguration;
import com.github.kklisura.cdt.services.exceptions.WebSocketServiceException;
import com.github.kklisura.cdt.services.impl.ChromeDevToolsServiceImpl;
import com.github.kklisura.cdt.services.impl.WebSocketServiceImpl;
import com.github.kklisura.cdt.services.invocation.CommandInvocationHandler;
import com.github.kklisura.cdt.services.utils.ProxyUtils;
import io.github.bonigarcia.wdm.WebDriverManager;
import net.lightbody.bmp.BrowserMobProxy;
import com.codeborne.selenide.Configuration;
import com.codeborne.selenide.WebDriverProvider;
import lombok.extern.slf4j.Slf4j;
import net.lightbody.bmp.BrowserMobProxyServer;
import net.lightbody.bmp.client.ClientUtil;
import net.lightbody.bmp.proxy.BlacklistEntry;
import net.lightbody.bmp.proxy.CaptureType;
import org.jetbrains.annotations.NotNull;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.ie.InternetExplorerDriver;
import org.openqa.selenium.ie.InternetExplorerOptions;
import org.openqa.selenium.opera.OperaDriver;
import org.openqa.selenium.opera.OperaOptions;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.LocalFileDetector;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.safari.SafariDriver;
import org.openqa.selenium.safari.SafariOptions;
import ru.alfabank.alfatest.cucumber.api.AkitaScenario;
import ru.alfabank.tests.core.helpers.BlackList;
import ru.alfabank.tests.core.helpers.PropertyLoader;

import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.codeborne.selenide.Browsers.*;
import static org.openqa.selenium.remote.CapabilityType.*;
import static ru.alfabank.tests.core.helpers.PropertyLoader.loadProperty;
import static ru.alfabank.tests.core.helpers.PropertyLoader.loadSystemPropertyOrDefault;

/**
 * ?????????????????? ??????????????????, ?????????????? ?????????????????? ?????????????????? ?????????? ???????????????? ?????? ????????????????, ?????????????????? Selenoid
 * ?????????????????? ?????????????? ?????????? ????????????????, ?????? ?????????????????? ????????????????????.
 * <p>
 * ????????????????, ?????????? ?????????????? ??????????????, ???????????? ????????????????, remote Url(?????? ?????????? ???????????????? ??????????), ???????????? ?? ???????????? ???????? ????????????????,
 * ?????? ?????????????????? ?????????????? ?????? ???????????? ?? Selenoid UI:
 * -Dbrowser=chrome -DbrowserVersion=63.0 -DremoteUrl=http://some/url -Dwidth=1200 -Dheight=800
 * -DselenoidSessionName=MyProjectName -Doptions=--load-extension=my-custom-extension
 * ???????? ???????????????? remoteUrl ???? ???????????? - ?????????? ?????????? ???????????????? ???????????????? ?? ???????????????? ???????????????? ?????????????????? ????????????.
 * ?????? ?????????????????????? ?????????? ?????????? ?????????????????????? ?? ???????????????????? options, ???????????????? ???? ????????????????.
 * ???????? ???????????? ???????????????? remoteUrl ?? browser, ???? ???????????? ???????????????? ???? ??????????????,
 * ???? ?????????????????? ?????????? ?????????????????????? ???????????? latest
 * ???????? ?????????????? ???? ???????????? - ???? ?????????????????? ?????????? ?????????????? chrome
 * ???? ?????????????????? ???????????? ???????? ???????????????? ?????? remote ?????????????? ?????????? 1920x1080
 * ?????????????????????????? ?????????????????????? ?????????????? ?? ???????????? ???????????????????? ???????????????? (-Dbrowser=mobile)
 * ???????? selenoidSessionName ???? ???????????? - ?????? ???????????? ?? Selenoid UI ???????????????????????? ???? ??????????
 * ?? ?????????????????? ????????????????????, ???? ?????????????? ?????????? ?????????????????????????? ???????????? ???????????????????? chrome ???????????????? (-Ddevice=iPhone 6)
 * ???????? ???????????? ???????????????? headless, ???? ???????????????? firefox ?? chrome ?????????? ?????????????????????? ?????? GUI (-Dheadless=true)
 */
@Slf4j
public class CustomDriverProvider implements WebDriverProvider {
    public static final String MOBILE_DRIVER = "mobile";
    public static final String BROWSER = "browser";
    public static final String REMOTE_URL = "remoteUrl";
    public static final String HEADLESS = "headless";
    public static final String WINDOW_WIDTH = "width";
    public static final String WINDOW_HEIGHT = "height";
    public static final String VERSION_LATEST = "latest";
    public static final String LOCAL = "local";
    public static final String TRUST_ALL_SERVERS = "trustAllServers";
    public static final String NEW_HAR = "har";
    public static final String SELENOID = "selenoid";
    public static final String ABORTED_NETWORK_REQUESTS_LIST_PROPERTY = "abortedNetworkRequestsList";
    private static final String SELENOID_SESSION_NAME = "selenoidSessionName";
    public static final int DEFAULT_WIDTH = 1920;
    public static final int DEFAULT_HEIGHT = 1080;
    private final List<String> abortedNetworkRequestsList = new ArrayList<>();

    private static final BrowserMobProxy PROXY = new BrowserMobProxyServer();
    private final String[] options = loadSystemPropertyOrDefault("options", "").split(" ");

    public static BrowserMobProxy getProxy() {
        return PROXY;
    }

    /**
     * ???????? ???????????????????? -Dproxy=true ???????????????? ????????????
     * har ?????? ?????????????????? ?????????????????????? ?? application.properties
     *
     * @param capabilities ???????????????????? ?????? ????????????????
     */
    private void enableProxy(DesiredCapabilities capabilities) {
        PROXY.setTrustAllServers(Boolean.parseBoolean(loadProperty(TRUST_ALL_SERVERS, "true")));
        PROXY.start();

        Proxy seleniumProxy = ClientUtil.createSeleniumProxy(PROXY);

        capabilities.setCapability(CapabilityType.PROXY, seleniumProxy);
        capabilities.setCapability(ACCEPT_SSL_CERTS, Boolean.valueOf(loadProperty(ACCEPT_SSL_CERTS, "true")));
        capabilities.setCapability(SUPPORTS_JAVASCRIPT, Boolean.valueOf(loadProperty(SUPPORTS_JAVASCRIPT, "true")));

        PROXY.enableHarCaptureTypes(CaptureType.REQUEST_CONTENT, CaptureType.REQUEST_HEADERS, CaptureType.RESPONSE_CONTENT, CaptureType.RESPONSE_HEADERS);
        PROXY.newHar(loadProperty(NEW_HAR));
    }

    @NotNull
    @Override
    public WebDriver createDriver(@NotNull Capabilities capabilities) {
        DesiredCapabilities desiredCapabilities = new DesiredCapabilities(capabilities);
        Configuration.browserSize = String.format("%sx%s", loadSystemPropertyOrDefault(WINDOW_WIDTH, DEFAULT_WIDTH),
                loadSystemPropertyOrDefault(WINDOW_HEIGHT, DEFAULT_HEIGHT));
        String expectedBrowser = loadSystemPropertyOrDefault(BROWSER, capabilities.getBrowserName());
        String remoteUrl = loadSystemPropertyOrDefault(REMOTE_URL, LOCAL);
        BlackList blackList = new BlackList();
        boolean isProxyMode = loadSystemPropertyOrDefault(CapabilityType.PROXY, false);
        if (isProxyMode) {
            enableProxy(desiredCapabilities);
        }

        log.info("remoteUrl=" + remoteUrl + " expectedBrowser= " + expectedBrowser + " BROWSER_VERSION=" + System.getProperty(CapabilityType.BROWSER_VERSION));

        switch (expectedBrowser.toLowerCase()) {
            case (FIREFOX):
                return LOCAL.equalsIgnoreCase(remoteUrl) ? createFirefoxDriver(desiredCapabilities) : getRemoteDriver(getFirefoxDriverOptions(desiredCapabilities), remoteUrl, blackList.getBlacklistEntries());
            case (MOBILE_DRIVER):
                return LOCAL.equalsIgnoreCase(remoteUrl) ? new ChromeDriver(getMobileChromeOptions(desiredCapabilities)) : getRemoteDriver(getMobileChromeOptions(desiredCapabilities), remoteUrl, blackList.getBlacklistEntries());
            case (OPERA):
                return LOCAL.equalsIgnoreCase(remoteUrl) ? createOperaDriver(desiredCapabilities) : getRemoteDriver(getOperaRemoteDriverOptions(desiredCapabilities), remoteUrl, blackList.getBlacklistEntries());
            case (SAFARI):
                return LOCAL.equalsIgnoreCase(remoteUrl) ? createSafariDriver(desiredCapabilities) : getRemoteDriver(getSafariDriverOptions(desiredCapabilities), remoteUrl, blackList.getBlacklistEntries());
            case (INTERNET_EXPLORER):
            case (IE):
                return LOCAL.equalsIgnoreCase(remoteUrl) ? createIEDriver(desiredCapabilities) : getRemoteDriver(getIEDriverOptions(desiredCapabilities), remoteUrl, blackList.getBlacklistEntries());
            case (EDGE):
                return LOCAL.equalsIgnoreCase(remoteUrl) ? createEdgeDriver(desiredCapabilities) : getRemoteDriver(getEdgeDriverOptions(desiredCapabilities), remoteUrl, blackList.getBlacklistEntries());
            default:
                return LOCAL.equalsIgnoreCase(remoteUrl) ? createChromeDriver(desiredCapabilities) : getRemoteDriver(getChromeDriverOptions(desiredCapabilities), remoteUrl, blackList.getBlacklistEntries());

        }
    }

    /**
     * ???????????? capabilities ?????? ?????????????? Remote ???????????????? ?????? Selenoid
     * ???????????????????? ?????????? ???? ???????????????????? ?????????????? ???????????????? ?????????? devTools
     *
     * @param capabilities - capabilities ?????? ???????????????????????????? ????????????????
     * @param remoteUrl    - url ?????? ?????????????? ????????????, ???????????????? http://remoteIP:4444/wd/hub
     * @return WebDriver
     */
    private WebDriver getRemoteDriver(MutableCapabilities capabilities, String remoteUrl) {
        log.info("---------------run Remote Driver---------------------");
        Boolean isSelenoidRun = loadSystemPropertyOrDefault(SELENOID, true);
        if (isSelenoidRun) {
            capabilities.setCapability("enableVNC", true);
            capabilities.setCapability("screenResolution", String.format("%sx%s", loadSystemPropertyOrDefault(WINDOW_WIDTH, DEFAULT_WIDTH),
                    loadSystemPropertyOrDefault(WINDOW_HEIGHT, DEFAULT_HEIGHT)));
            String sessionName = loadSystemPropertyOrDefault(SELENOID_SESSION_NAME, "");
            if (!sessionName.isEmpty()) {
                capabilities.setCapability("name", String.format("%s %s", sessionName, AkitaScenario.getInstance().getScenario().getName()));
            }
        }
        try {
            RemoteWebDriver remoteWebDriver = new RemoteWebDriver(
                    URI.create(remoteUrl).toURL(),
                    capabilities
            );
            remoteWebDriver.setFileDetector(new LocalFileDetector());
            abortedNetworkRequestsList.addAll(Arrays.asList(loadSystemPropertyOrDefault(ABORTED_NETWORK_REQUESTS_LIST_PROPERTY, "")
                    .replace(" ", "")
                    .split(",")));
            if(!abortedNetworkRequestsList.isEmpty()) {
                setAbortedNetworkRequests(remoteWebDriver, remoteUrl, abortedNetworkRequestsList);
            }
            return remoteWebDriver;
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * ???????????????? ???????????? ?? devTools ?????????? webSocket ?? ???????????????? ?????????????? ?????????????? ?????????? ??????????????????????
     * @param remoteWebDriver - ?????????????????????????????????????????? ??????????????
     * @param remoteUrl - url ?????? ?????????????? ????????????, ???????????????? http://remoteIP:4444/wd/hub
     */

    private static void setAbortedNetworkRequests(RemoteWebDriver remoteWebDriver, String remoteUrl, List<String> abortedNetworkRequestsList) {
        ChromeDevToolsService devTools = getDevTools(remoteWebDriver, remoteUrl);
        log.info("---------------Aborted Requests---------------------");
        abortedNetworkRequestsList.forEach(log::info);
        Fetch fetch = devTools.getFetch();
        fetch.onRequestPaused(
                e -> fetch.failRequest(e.getRequestId(), ErrorReason.FAILED)
        );
        List<RequestPattern> requestPatternList = new ArrayList<>();
        abortedNetworkRequestsList.forEach(request -> {
            RequestPattern requestPattern = new RequestPattern();
            requestPattern.setUrlPattern(request);
            requestPatternList.add(requestPattern);
        });
        fetch.enable(requestPatternList, true);
    }

    /**
     * ???????????????? ???????????? ?? devTools ?????????? webSocket
     * @param remoteWebDriver - ?????????????????????????????????????????? ??????????????
     * @param remoteUrl - url ?????? ?????????????? ????????????, ???????????????? http://remoteIP:4444/wd/hub
     * @return - ???????????????????? ?????????????????? ChromeDevToolsService ?????? ???????????????????? ???????????? ?? ??????
     */

    private static ChromeDevToolsService getDevTools(RemoteWebDriver remoteWebDriver, String remoteUrl) {
        ChromeDevToolsService devtools;
        WebSocketService webSocketService = null;

        Pattern pattern = Pattern.compile("([a-zA-Z0-9-]+\\.)*[a-zA-Z0-9-]+:[0-9]+");
        Matcher matcher = pattern.matcher(remoteUrl);
        if(matcher.find()) {
            try {
                webSocketService = WebSocketServiceImpl.create(new URI(String.format("ws://%s/devtools/%s/page", matcher.group(), remoteWebDriver.getSessionId())));
            } catch (WebSocketServiceException | URISyntaxException e) {
                e.printStackTrace();
            }
        } else throw new InvalidArgumentException("something wrong with remoteUrl, please check your gradle.properties file. Your remoteUrl: " + remoteUrl);
        CommandInvocationHandler commandInvocationHandler = new CommandInvocationHandler();
        Map<Method, Object> commandsCache = new ConcurrentHashMap<>();
        devtools =
                ProxyUtils.createProxyFromAbstract(
                        ChromeDevToolsServiceImpl.class,
                        new Class[] { WebSocketService.class, ChromeDevToolsServiceConfiguration.class },
                        new Object[] { webSocketService, new ChromeDevToolsServiceConfiguration() },
                        (unused, method, args) ->
                                commandsCache.computeIfAbsent(
                                        method,
                                        key -> {
                                            Class<?> returnType = method.getReturnType();
                                            return ProxyUtils.createProxy(returnType, commandInvocationHandler);
                                        }));
        commandInvocationHandler.setChromeDevToolsService(devtools);
        return devtools;
    }

    /**
     * ???????????? capabilities ?????? ?????????????? Remote ???????????????? ?????? Selenoid
     * ???? ?????????????? ?????????????????????????????? URL, ?????????????? ?????????????????????? ?? Blacklist
     * URL ?????? ???????????????????? ?? Blacklist ?????????? ???????? ?????????????? ?? ?????????????? ???????????????????? ??????????????????
     *
     * @param capabilities ???????????????????? ?????? ????????????????
     * @param remoteUrl ???????????? ?????? ???????????????????? ??????????????
     * @param blacklistEntries - ???????????? url ?????? ???????????????????? ?? Blacklist
     * @return WebDriver
     */
    private WebDriver getRemoteDriver(MutableCapabilities capabilities, String remoteUrl, List<BlacklistEntry> blacklistEntries) {
        PROXY.setBlacklist(blacklistEntries);
        return getRemoteDriver(capabilities, remoteUrl);
    }

    /**
     * ?????????????????????????? ChromeOptions ?????? ?????????????? google chrome ???????????????????????? ???????????? ???????????????????? ???????????????????? (???? ?????????????????? nexus 5)
     * ???????????????? ???????????????????? ???????????????????? (device) ?????????? ???????? ???????????? ?????????? ?????????????????? ????????????????????
     *
     * @return ChromeOptions
     */
    private ChromeOptions getMobileChromeOptions(DesiredCapabilities capabilities) {
        log.info("---------------run CustomMobileDriver---------------------");
        String mobileDeviceName = loadSystemPropertyOrDefault("device", "Nexus 5");
        ChromeOptions chromeOptions = new ChromeOptions().addArguments("disable-extensions",
                "test-type", "no-default-browser-check", "ignore-certificate-errors");

        Map<String, String> mobileEmulation = new HashMap<>();
        chromeOptions.setHeadless(getHeadless());
        mobileEmulation.put("deviceName", mobileDeviceName);
        chromeOptions.setExperimentalOption("mobileEmulation", mobileEmulation);
        chromeOptions.merge(capabilities);
        return chromeOptions;
    }

    /**
     * ???????????? options ?????? ?????????????? Chrome ????????????????
     * options ?????????? ????????????????????, ?????? ?????????????????? ????????????????????, ???????????????? -Doptions=--load-extension=my-custom-extension
     *
     * @return ChromeOptions
     */
    private ChromeOptions getChromeDriverOptions(DesiredCapabilities capabilities) {
        log.info("---------------Chrome Driver---------------------");
        ChromeOptions chromeOptions = !options[0].equals("") ? new ChromeOptions().addArguments(options) : new ChromeOptions();
        chromeOptions.setCapability(CapabilityType.BROWSER_VERSION, loadSystemPropertyOrDefault(CapabilityType.BROWSER_VERSION, VERSION_LATEST));
        chromeOptions.setHeadless(getHeadless());
        chromeOptions.merge(capabilities);
        return chromeOptions;
    }

    /**
     * ???????????? options ?????? ?????????????? Firefox ????????????????
     * options ?????????? ????????????????????, ?????? ?????????????????? ????????????????????, ???????????????? -Doptions=--load-extension=my-custom-extension
     *
     * @return FirefoxOptions
     */
    private FirefoxOptions getFirefoxDriverOptions(DesiredCapabilities capabilities) {
        log.info("---------------Firefox Driver---------------------");
        FirefoxOptions firefoxOptions = !options[0].equals("") ? new FirefoxOptions().addArguments(options) : new FirefoxOptions();
        capabilities.setVersion(loadSystemPropertyOrDefault(CapabilityType.BROWSER_VERSION, VERSION_LATEST));
        firefoxOptions.setHeadless(getHeadless());
        firefoxOptions.merge(capabilities);
        return firefoxOptions;
    }

    /**
     * ???????????? options ?????? ?????????????? Opera ????????????????
     * options ?????????? ????????????????????, ?????? ?????????????????? ????????????????????, ???????????????? -Doptions=--load-extension=my-custom-extension
     *
     * @return operaOptions
     */
    private OperaOptions getOperaDriverOptions(DesiredCapabilities capabilities) {
        log.info("---------------Opera Driver---------------------");
        OperaOptions operaOptions = !options[0].equals("") ? new OperaOptions().addArguments(options) : new OperaOptions();
        operaOptions.setCapability(CapabilityType.BROWSER_VERSION, loadSystemPropertyOrDefault(CapabilityType.BROWSER_VERSION, VERSION_LATEST));
        operaOptions.merge(capabilities);
        return operaOptions;
    }

    /**
     * ???????????? options ?????? ?????????????? Opera ???????????????? ?? ???????????????????? Selenoid
     * options ?????????? ????????????????????, ?????? ?????????????????? ????????????????????, ???????????????? -Doptions=--load-extension=my-custom-extension
     *
     * @return operaOptions
     */
    private OperaOptions getOperaRemoteDriverOptions(DesiredCapabilities capabilities) {
        log.info("---------------Opera Driver---------------------");
        OperaOptions operaOptions = !this.options[0].equals("") ? (new OperaOptions()).addArguments(this.options) : new OperaOptions();
        operaOptions.setCapability("browserVersion", PropertyLoader.loadSystemPropertyOrDefault("browserVersion", "latest"));
        operaOptions.setCapability("browserName", "opera");
        operaOptions.setBinary(PropertyLoader.loadSystemPropertyOrDefault("webdriver.opera.driver", "/usr/bin/opera"));
        operaOptions.merge(capabilities);
        return operaOptions;
    }


    /**
     * ???????????? options ?????? ?????????????? IE ????????????????
     * options ?????????? ????????????????????, ?????? ?????????????????? ????????????????????, ???????????????? -Doptions=--load-extension=my-custom-extension
     *
     * @return internetExplorerOptions
     */
    private InternetExplorerOptions getIEDriverOptions(DesiredCapabilities capabilities) {
        log.info("---------------IE Driver---------------------");
        InternetExplorerOptions internetExplorerOptions = !options[0].equals("") ? new InternetExplorerOptions().addCommandSwitches(options) : new InternetExplorerOptions();
        // internetExplorerOptions.setCapability(CapabilityType.BROWSER_VERSION, "11");
        internetExplorerOptions.setCapability("ie.usePerProcessProxy", "true");
        internetExplorerOptions.setCapability("requireWindowFocus", "false");
        internetExplorerOptions.setCapability("ie.browserCommandLineSwitches", "-private");
        internetExplorerOptions.setCapability("ie.ensureCleanSession", "true");
        internetExplorerOptions.merge(capabilities);
        return internetExplorerOptions;
    }

    /**
     * ???????????? options ?????? ?????????????? Edge ????????????????
     * options ?????????? ????????????????????, ?????? ?????????????????? ????????????????????, ???????????????? -Doptions=--load-extension=my-custom-extension
     *
     * @return edgeOptions
     */
    private EdgeOptions getEdgeDriverOptions(DesiredCapabilities capabilities) {
        log.info("---------------Edge Driver---------------------");
        EdgeOptions edgeOptions = new EdgeOptions();
        edgeOptions.setCapability(CapabilityType.BROWSER_VERSION, loadSystemPropertyOrDefault(CapabilityType.BROWSER_VERSION, VERSION_LATEST));
        capabilities.setPlatform(Platform.WINDOWS);
        edgeOptions.merge(capabilities);
        return edgeOptions;
    }

    /**
     * ???????????? options ?????? ?????????????? Safari ????????????????
     * options ?????????? ????????????????????, ?????? ?????????????????? ????????????????????, ???????????????? -Doptions=--load-extension=my-custom-extension
     *
     * @return SafariOptions
     */
    private SafariOptions getSafariDriverOptions(DesiredCapabilities capabilities) {
        log.info("---------------Safari Driver---------------------");
        SafariOptions safariOptions = new SafariOptions();
        safariOptions.setCapability(CapabilityType.BROWSER_VERSION, loadSystemPropertyOrDefault(CapabilityType.BROWSER_VERSION, VERSION_LATEST));
        safariOptions.merge(capabilities);
        return safariOptions;
    }

    /**
     * ?????????????? ?????????????????? ChromeDriver ?? ?????????????????????? capabilities ?? window dimensions
     *
     * @return WebDriver
     */
    private WebDriver createChromeDriver(DesiredCapabilities capabilities) {
        WebDriverManager.chromedriver().setup();
        return new ChromeDriver(getChromeDriverOptions(capabilities));
    }

    /**
     * ?????????????? ?????????????????? FirefoxDriver ?? ?????????????????????? capabilities ?? window dimensions
     *
     * @return WebDriver
     */
    private WebDriver createFirefoxDriver(DesiredCapabilities capabilities) {
        WebDriverManager.firefoxdriver().setup();
        return new FirefoxDriver(getFirefoxDriverOptions(capabilities));
    }

    /**
     * ?????????????? ?????????????????? OperaDriver ?? ?????????????????????? capabilities ?? window dimensions
     *
     * @return WebDriver
     */
    private WebDriver createOperaDriver(DesiredCapabilities capabilities) {
        WebDriverManager.operadriver().setup();
        return new OperaDriver(getOperaDriverOptions(capabilities));
    }

    /**
     * ?????????????? ?????????????????? InternetExplorerDriver ?? ?????????????????????? capabilities ?? window dimensions
     *
     * @return WebDriver
     */
    private WebDriver createIEDriver(DesiredCapabilities capabilities) {
        WebDriverManager.iedriver().setup();
        return new InternetExplorerDriver(getIEDriverOptions(capabilities));
    }

    /**
     * ?????????????? ?????????????????? EdgeDriver ?? ?????????????????????? capabilities ?? window dimensions
     *
     * @return WebDriver
     */
    private WebDriver createEdgeDriver(DesiredCapabilities capabilities) {
        WebDriverManager.edgedriver().setup();
        return new EdgeDriver(getEdgeDriverOptions(capabilities));
    }

    /**
     * ?????????????? ?????????????????? SafariDriver ?? ?????????????????????? capabilities ?? window dimensions
     *
     * @return WebDriver
     */
    private WebDriver createSafariDriver(DesiredCapabilities capabilities) {
        WebDriverManager.safaridriver().setup();
        return new SafariDriver(getSafariDriverOptions(capabilities));
    }

    /**
     * ???????????? ???????????????? ?????????????????? headless ???? application.properties
     * ?? selenide.headless ???? ?????????????????? ??????????????????
     *
     * @return ???????????????? ?????????????????? headless ?????? false, ???????? ???? ??????????????????????
     */
    private Boolean getHeadless() {
        Boolean isHeadlessApp = loadSystemPropertyOrDefault(HEADLESS, false);
        Boolean isHeadlessSys = Boolean.parseBoolean(System.getProperty("selenide." + HEADLESS, "false"));
        return isHeadlessApp || isHeadlessSys;
    }
}

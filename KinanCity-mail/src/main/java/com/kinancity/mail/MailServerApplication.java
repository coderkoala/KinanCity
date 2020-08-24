package com.kinancity.mail;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import com.kinancity.mail.mailchanger.*;
import com.kinancity.mail.mailchanger.impl.StaticPasswordProvider;
import org.subethamail.wiser.Wiser;

import com.kinancity.mail.activator.LinkActivator;
import com.kinancity.mail.activator.MultiThreadQueueLinkActivator;
import com.kinancity.mail.activator.QueueLinkActivator;
import com.kinancity.mail.activator.ToFileLinkActivator;
import com.kinancity.mail.activator.limiter.RateLimiter;
import com.kinancity.mail.proxy.HttpProxy;
import com.kinancity.mail.tester.ThrottleTester;

public class MailServerApplication {

	private static final String CONFIG_FILE = "config.properties";

	private static Properties config;

	public static void main(String[] args) {

		loadConfig();

		String mode = "direct";

		if (args.length > 0) {
			mode = args[0].toLowerCase();
		}

		if (mode.equals("test")) {
			System.out.println("Start in Tester Mode");

			ThrottleTester tester = new ThrottleTester();

			if (args.length > 1) {
				tester.setDelay(Integer.parseInt(args[1]));
			}

			if (args.length > 2) {
				tester.setErrorDelay(Integer.parseInt(args[2]));
			}

			if (args.length > 3) {
				tester.setPauseAfterNRequests(Integer.parseInt(args[3]));
			}

			if (args.length > 4) {
				tester.setDelayAfterNRequests(Integer.parseInt(args[4]));
			}

			tester.start();
		} else if (mode.equals("file")) {

			String filePath = "links.log";
			if (args.length > 1) {
				filePath = args[1];
			}

			LinkActivator activator = getQueueLinkActivator();
			activator.start();

			System.out.println("Started in file Mode");
			new SkippedFileProcessor(activator, filePath).process();

		} else {
			LinkActivator activator = getQueueLinkActivator();
			EmailChanger emailChanger = null;

			if (mode.equals("log")) {
				System.out.println("Started in Log Mode");
				activator = new ToFileLinkActivator();
				emailChanger = new ToFileEmailChanger();
			} else {
				System.out.println("Started in Direct Mode");
				emailChanger = getEmailChanger();
			}
			activator.start();

			// Start Wiser server
			Wiser wiser = new Wiser();
			wiser.setPort(25);
			wiser.setHostname("localhost");

			KcMessageHandlerFactory handlerFactory = new KcMessageHandlerFactory(activator, emailChanger);
			boolean disableDomainFilter = config.getProperty("disableDomainFilter", "false").equals("true");
			if (disableDomainFilter) {
				handlerFactory.setAcceptAllFrom(true);
			}

			if(activator != null ) {
				System.out.println("Email Activation is Enabled");
			}

			if(emailChanger != null) {
				System.out.println("Email Change is Enabled");
			}

			wiser.getServer().setMessageHandlerFactory(handlerFactory);
			wiser.start();
		}
	}

	public static void loadConfig() {
		config = new Properties();

		try {
			File configFile = new File(CONFIG_FILE);
			if (!configFile.exists()) {
				System.out.println("Missing configuration file " + CONFIG_FILE);
				return;
			}
			InputStream in = new FileInputStream(configFile);
			config.load(in);
			in.close();
		} catch (IOException e) {
			System.out.println("Error loading configuration file " + CONFIG_FILE);
		}
	}

	private static EmailChanger getEmailChanger() {
		String isActive = config.getProperty("emailChanger.active");
		if(isActive == null || isActive.equals("true")) {
			String mailPasswordConfig = config.getProperty("emailChanger.password");
			PasswordProvider passwordProvider = PasswordProviderFactory.getPasswordProvider(mailPasswordConfig);
			return new DirectEmailChanger(passwordProvider);
		} else {
			return null;
		}
	}

	public static LinkActivator getQueueLinkActivator() {

		QueueLinkActivator activator;

		String multithreadActive = config.getProperty("multithread.enable");
		if (multithreadActive != null && !multithreadActive.equals("false")) {
			MultiThreadQueueLinkActivator mtactivator = new MultiThreadQueueLinkActivator();
			
			String multithreadNbThread = config.getProperty("multithread.nbThread");
			if(multithreadNbThread != null){
				mtactivator.setNbThreads(Integer.parseInt(multithreadNbThread));	
			}
			activator = mtactivator;
		} else {
			activator = new QueueLinkActivator();
		}

		String proxy = config.getProperty("proxy");
		if (proxy != null) {
			if (proxy.contains("|")) {
				List<String> proxies = new LinkedList<String>(Arrays.asList(proxy.split(Pattern.quote("|"))));
				String initialProxy = proxies.get(0);
				HttpProxy httpProxy = HttpProxy.fromURI(initialProxy);
				System.out.println("Using proxy " + httpProxy);
				activator.setHttpProxy(httpProxy);

				proxies.remove(0);
				for (String backupProxyStr : proxies) {
					HttpProxy backupProxy = HttpProxy.fromURI(backupProxyStr);
					httpProxy.getOtherProxies().add(backupProxy);
					System.out.println("with backup proxy " + backupProxy);
				}

			} else {
				HttpProxy httpProxy = HttpProxy.fromURI(proxy);
				System.out.println("Using proxy " + httpProxy);
				activator.setHttpProxy(httpProxy);
			}
		}

		String limiterActive = config.getProperty("limiter.enable");
		if (limiterActive != null && !limiterActive.equals("false")) {
			String period = config.getProperty("limiter.period");
			String nb = config.getProperty("limiter.nb");
			String pause = config.getProperty("limiter.pause");

			RateLimiter limiter = new RateLimiter();
			if (period != null) {
				limiter.setPeriodInSeconds(Integer.parseInt(period));
			}
			if (nb != null) {
				limiter.setNbPerPeriod(Integer.parseInt(nb));
			}
			if (pause != null) {
				limiter.setLimiterPause(Integer.parseInt(pause));
			}

			System.out.println("Using limiter " + limiter);
			activator.setLimiter(limiter);
		}
		
		String proxyBanActive = config.getProperty("proxy.banProxyOn403");
		if (proxyBanActive != null && !proxyBanActive.equals("false")) {
			activator.setBanProxyOn403(true);
		}
		
		String alwaysSwitchActive = config.getProperty("proxy.switchProxyOnSuccess");
		if (alwaysSwitchActive != null && !alwaysSwitchActive.equals("false")) {
			activator.setSwitchProxyOnSuccess(true);
		}
		
		return activator;
	}

}

/*
 * ************************************************************
 *     Arbat - Open Source Java CORBA services implementation *
 * ************************************************************
 *   $Id: EventChannelImpl.java,v 1.7 2006/11/22 02:10:42 drogatkin Exp $
 */
package org.arbat.eventchannel;

import java.util.List;
import java.util.ArrayList;
import java.util.Properties;
import java.util.Iterator;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.io.IOException;

import org.omg.CORBA.Any;
import org.omg.CORBA.BooleanHolder;
import org.omg.CORBA.BAD_PARAM;
import org.omg.CORBA.ORB;

import org.omg.CosEventComm.PushConsumer;
import org.omg.CosEventComm.PullConsumer;
import org.omg.CosEventComm.PushSupplier;
import org.omg.CosEventComm.PullSupplier;
import org.omg.CosEventComm.Disconnected;

import org.omg.CosEventChannelAdmin.ConsumerAdmin;
import org.omg.CosEventChannelAdmin.SupplierAdmin;
import org.omg.CosEventChannelAdmin.ProxyPushSupplier;
import org.omg.CosEventChannelAdmin.ProxyPullSupplier;
import org.omg.CosEventChannelAdmin.ProxyPushConsumer;
import org.omg.CosEventChannelAdmin.ProxyPullConsumer;
import org.omg.CosEventChannelAdmin.AlreadyConnected;
import org.omg.CosEventChannelAdmin.TypeError;
import org.omg.CosEventChannelAdmin.ProxyPullSupplierHelper;
import org.omg.CosEventChannelAdmin.ProxyPushSupplierHelper;
import org.omg.CosEventChannelAdmin.ProxyPushSupplierPOA;
import org.omg.CosEventChannelAdmin.EventChannelPOA;
import org.omg.CosEventChannelAdmin.ConsumerAdminPOA;
import org.omg.CosEventChannelAdmin.ConsumerAdminHelper;
import org.omg.CosEventChannelAdmin.ProxyPullSupplierPOA;
import org.omg.CosEventChannelAdmin.SupplierAdminHelper;
import org.omg.CosEventChannelAdmin.SupplierAdminPOA;
import org.omg.CosEventChannelAdmin.ProxyPullConsumerHelper;
import org.omg.CosEventChannelAdmin.ProxyPullConsumerPOA;
import org.omg.CosEventChannelAdmin.ProxyPushConsumerHelper;
import org.omg.CosEventChannelAdmin.ProxyPushConsumerPOA;

import org.omg.PortableServer.POAManagerPackage.AdapterInactive;
import org.omg.PortableServer.POAManagerPackage.State;


public class EventChannelImpl extends EventChannelPOA {
    protected org.omg.PortableServer.POA rootPoa;
	protected ConsumerAdmin consumerAdmin;
	protected SupplierAdmin supplierAdmin;
	protected List<PushConsumer> pushConsumers;
	protected List<PullConsumer> pullConsumers;
	protected List<PushSupplier> pushSuppliers;
	protected List<PullSupplier> pullSuppliers;

	protected ThreadPoolExecutor threadPool;
	public static final String CONFIG_PROP_CLASS = "org.arbat.eventchannel.Configurator";
	protected static final String PROJECT_NAME = "arbat";
	protected static final String THREADS_PROP = "org.arbat.eventchannel.startThreads";
	protected static final String MAX_THREADS_PROP = "org.arbat.eventchannel.maxThreads";
	protected static final String QUEUE_CAP_PROP = "org.arbat.eventchannel.queueCapacity";
	protected static final String THREAD_ALIVE_SEC_PROP = "org.arbat.eventchannel.keepThreadAliveSec";
	protected static final String ESTIMATE_CONSUMERS_PROP = "org.arbat.eventchannel.estimateNumConsumers";

	/**
	 * parameters to set event queue size 100 worker thread initial number 10
	 * worker thread max number 15 keep alive 180 secs execution queue size
	 */
	public EventChannelImpl() {
		// TODO: considering using java.util.prefs.Preferences
		Properties configuration = null;
		String configClassName = System.getProperty(CONFIG_PROP_CLASS);
		if (configClassName != null) {
			try {
				configuration = (Properties) Class.forName(configClassName).newInstance();
			} catch (Error | Exception e) {
				errorReport("Can't instantiate config class, default configuration will be used.", null, e);
			}
		}
		init(configuration);
	}

	public EventChannelImpl(ORB orb, Properties properties) {
        try {
            rootPoa = org.omg.PortableServer.POAHelper.narrow(orb.resolve_initial_references("RootPOA"));

            if (State.ACTIVE.equals(rootPoa.the_POAManager().get_state()) == false) {
                throw new AdapterInactive("Root POA is inactive or other not operable state, channel isn't operable");
			}
			init(properties);
        } catch (org.omg.CORBA.UserException ue) {
            errorReport("Problem in obtaining POA root.", null, ue);
        }
	}

	protected void init(Properties configuration) {
		if (configuration == null) {
			configuration = new Properties();
			try {
				configuration.load(getClass().getResourceAsStream("/resource/configuration.properties"));
			} catch (IOException | NullPointerException e) {
				errorReport("Default configuration is not available, check packaging.", null, e);
			}
		}
		if (_debug) {
			debugReport("Event channel configuration: %s", new Object[]{configuration}); // debug
		}
		int preSize = getIntPropWithDefault(ESTIMATE_CONSUMERS_PROP, 10, configuration);
		pushConsumers = new ArrayList<>(preSize);
		pullConsumers = new ArrayList<>(preSize);
		pushSuppliers = new ArrayList<>(preSize);
		pullSuppliers = new ArrayList<>(preSize);
		threadPool = new ThreadPoolExecutor(getIntPropWithDefault(THREADS_PROP, 10, configuration),
				getIntPropWithDefault(MAX_THREADS_PROP, 20, configuration),
				getIntPropWithDefault(THREAD_ALIVE_SEC_PROP, 180, configuration),
				TimeUnit.SECONDS,
				new LinkedBlockingQueue<Runnable>(getIntPropWithDefault(QUEUE_CAP_PROP, Integer.MAX_VALUE, configuration)));
	}

	protected void errorReport(String errorMessage, Object[] parameters, Throwable t) {
		String message = PROJECT_NAME + ":error " + errorMessage;
		if (parameters != null) {
			message = String.format(message, parameters);
		}
		if (t != null) {
			Logger.getLogger(PROJECT_NAME).throwing(message, "", t);
		} else {
			Logger.getLogger(PROJECT_NAME).severe(message);
		}
	}

	protected void debugReport(String debugMessage, Object[] parameters) {
		if (_debug) {
			String message = PROJECT_NAME + ":debug " + debugMessage;
			if (parameters != null) {
				message = String.format(message, parameters);
			}
			Logger.getLogger(PROJECT_NAME).info(message);
		}
	}

	// TODO: push in some util class
	protected int getIntPropWithDefault(String name, int defVal, Properties properties) {
		int result = defVal;
		try {
			result = Integer.parseInt(properties.getProperty(name));
		} catch (Exception e) {
			// use default
		}
		return result;
	}

	public ConsumerAdmin for_consumers() {
		synchronized (this) {
			if (consumerAdmin == null) {
                try {
                    consumerAdmin = ConsumerAdminHelper.narrow(rootPoa.servant_to_reference(new ConsumerAdminImpl()));
                } catch (org.omg.CORBA.UserException ue) {
                    errorReport("ConsumerAdminImpl", null, ue);
                }
			}
		}
		return consumerAdmin;
	}

	public SupplierAdmin for_suppliers() {
		synchronized (this) {
			if (supplierAdmin == null) {
                try {
                    supplierAdmin = SupplierAdminHelper.narrow(rootPoa.servant_to_reference(new SupplierAdminImpl()));
                } catch (org.omg.CORBA.UserException ue) {
                    errorReport("SupplierAdminImpl", null, ue);
                }
			}
		}
		return supplierAdmin;
	}

	public void destroy () {
	}

	class ConsumerAdminImpl extends ConsumerAdminPOA {
		ProxyPushSupplier proxyPushSupplier;
		ProxyPullSupplier proxyPullSupplier;
		public ProxyPushSupplier obtain_push_supplier() {
			synchronized (this) {
				if (proxyPushSupplier == null) {
                    try {
                        proxyPushSupplier = ProxyPushSupplierHelper.narrow(rootPoa.servant_to_reference(new ProxyPushSupplierImpl()));
                    } catch (org.omg.CORBA.UserException ue) {
                        errorReport("ProxyPushSupplierImpl", null, ue);
                    }
				}
			}
			return proxyPushSupplier;
		}

		public ProxyPullSupplier obtain_pull_supplier() {
			synchronized (this) {
				if (proxyPullSupplier == null) {
                    try {
                        proxyPullSupplier = ProxyPullSupplierHelper.narrow(rootPoa.servant_to_reference(new ProxyPullSupplierImpl()));
                    } catch (org.omg.CORBA.UserException ue) {
                        errorReport("ProxyPullSupplierImpl", null, ue);
                    }
				}
			}
			return proxyPullSupplier;
		}
	}

	class ProxyPushSupplierImpl extends ProxyPushSupplierPOA {
		public void connect_push_consumer(PushConsumer push_consumer) throws AlreadyConnected, TypeError {
			if (push_consumer == null) {
				throw new BAD_PARAM();
			}
			if (push_consumer instanceof Object == false) {
				throw new TypeError(push_consumer.getClass().getName());
			}
			synchronized (pushConsumers) {
				if (pushConsumers.contains(push_consumer)) {
					throw new AlreadyConnected();
				}
				pushConsumers.add(push_consumer);
				if (_debug) {
					debugReport("Added push cons: %s\n", new Object[] {push_consumer}); // debug
				}
			}
		}

		public void disconnect_push_supplier() {
			for (PushConsumer pushConsumer : pushConsumers) {
				pushConsumer.disconnect_push_consumer ();
			}
		}
	}

	class ProxyPullSupplierImpl extends ProxyPullSupplierPOA {
		public void connect_pull_consumer(PullConsumer pull_consumer) throws AlreadyConnected {
			synchronized (pullConsumers) {
				if (pullConsumers.contains(pull_consumer)) {
					throw new AlreadyConnected();
				}
				pullConsumers.add(pull_consumer);
			}
		}

		public Any pull() throws Disconnected {
			return null;
		}

		public Any try_pull(BooleanHolder has_event) throws Disconnected {
			return null;
		}

		public void disconnect_pull_supplier() {
			for (PullConsumer pullConsumer : pullConsumers) {
				pullConsumer.disconnect_pull_consumer();
			}
		}
	}

	class SupplierAdminImpl extends SupplierAdminPOA {
		ProxyPushConsumer proxyPushConsumer = null;
		ProxyPullConsumer proxyPullConsumer = null;

		public ProxyPushConsumer obtain_push_consumer() {
			synchronized (this) {
				if (proxyPushConsumer == null) {
                    try {
                        proxyPushConsumer = ProxyPushConsumerHelper.narrow(rootPoa.servant_to_reference(new ProxyPushConsumerImpl()));
                    } catch (org.omg.CORBA.UserException ue) {
                        errorReport("ProxyPushConsumerImpl", null, ue);
                    }
				}
			}
			return proxyPushConsumer;
		}

		public ProxyPullConsumer obtain_pull_consumer() {
			synchronized (this) {
				if (proxyPullConsumer == null) {
                    try {
                        proxyPullConsumer = ProxyPullConsumerHelper.narrow(rootPoa.servant_to_reference(new ProxyPullConsumerImpl()));
                    } catch (org.omg.CORBA.UserException ue) {
                        errorReport("ProxyPullConsumerImpl", null, ue);
                    }
				}
			}
			return proxyPullConsumer;
		}
	}

	class ProxyPushConsumerImpl extends ProxyPushConsumerPOA {
		public void connect_push_supplier (PushSupplier push_supplier) throws AlreadyConnected {
			if (push_supplier != null) {
				if (pushSuppliers.contains(push_supplier)) {
					throw new AlreadyConnected();
				}
				pushSuppliers.add(push_supplier);
			}
		}
		public void push(final Any data) throws Disconnected {
			threadPool.execute(new Runnable() {
				public void run() {
					// consider CopyOnWriteArrayList
					List<PushConsumer> clonePushConsumers;
					synchronized (pushConsumers) {
						clonePushConsumers = (List<PushConsumer>) ((ArrayList) pushConsumers).clone();
					}
					if (_debug) {
						debugReport("Notifying : %s cons-s.\n", new Object[]{clonePushConsumers}); // debug
					}
					for (PushConsumer pushConsumer:clonePushConsumers) {
						final PushConsumer push_consumer = pushConsumer;
						threadPool.execute(new Runnable() {
								public void run() {
									try {
										push_consumer.push(data);
									} catch (Disconnected | org.omg.CORBA.COMM_FAILURE | org.omg.CORBA.TRANSIENT e) {
										removeBadConsumer(push_consumer);
									}
								}
						    });
					}
				}
				});
		}

		public void disconnect_push_consumer() {
			for (PushSupplier pushSupplier : pushSuppliers) {
				pushSupplier.disconnect_push_supplier();
			}
		}

		protected void removeBadConsumer(PushConsumer push_consumer) {
			synchronized (pushConsumers) {
				Iterator <PushConsumer> pci = pushConsumers.iterator();
				while (pci.hasNext()) {
					if (push_consumer._is_equivalent(pci.next())) {
						pci.remove();
						if (_debug) {
							debugReport("removed push cons: %s\n", new Object[]{push_consumer}); // debug
						}
						break; // can be more that one entry?
					}
				}
			}
			try {
				if (push_consumer._non_existent() == false) {
					push_consumer.disconnect_push_consumer();
				}
			} catch (org.omg.CORBA.SystemException ce) { // obviously fails
			}
		}
	}

	class ProxyPullConsumerImpl extends ProxyPullConsumerPOA {
		public void connect_pull_supplier(PullSupplier pull_supplier) throws AlreadyConnected, TypeError {
			if (pull_supplier == null) {
				throw new BAD_PARAM();
			}
		}

		public void disconnect_pull_consumer () {
		}
	}

	private static final boolean _debug = false;
}

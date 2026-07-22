package com.example.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cloud.environment.servicebinding.api.DefaultServiceBindingAccessor;
import com.sap.cloud.environment.servicebinding.api.ServiceBinding;
import com.sap.cloud.environment.servicebinding.api.exception.ServiceBindingAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ServiceCredentialsAccessor {
    private static final Logger logger = LoggerFactory.getLogger(ServiceCredentialsAccessor.class);
    static final String SERVICE_BINDING_NAME = "credstore";

    private final ServiceCredentials credentials;

    public ServiceCredentialsAccessor() {
        this.credentials = loadCredentials();
    }

    public ServiceCredentials getCredentials() {
        return credentials;
    }

    private ServiceCredentials loadCredentials() {
        logger.debug("Getting {} service credentials", SERVICE_BINDING_NAME);
        ServiceBinding serviceBinding = loadServiceBinding();
        return new ObjectMapper().convertValue(serviceBinding.getCredentials(), ServiceCredentials.class);
    }

    private ServiceBinding loadServiceBinding() {
        logger.debug("Getting {} service binding", SERVICE_BINDING_NAME);
        List<ServiceBinding> allServiceBindings = DefaultServiceBindingAccessor.getInstance().getServiceBindings();

        return allServiceBindings.stream()
                .filter(binding -> SERVICE_BINDING_NAME.equalsIgnoreCase(
                        binding.getServiceName().orElseThrow(() ->
                                new ServiceBindingAccessException(
                                        String.format("Service binding with name [%s] not found", SERVICE_BINDING_NAME)))))
                .findFirst()
                .orElseThrow(() -> new ServiceBindingAccessException(
                        String.format("Failed to find %s service binding!", SERVICE_BINDING_NAME)));
    }
}

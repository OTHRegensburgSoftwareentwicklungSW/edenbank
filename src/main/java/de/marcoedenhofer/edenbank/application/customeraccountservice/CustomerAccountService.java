package de.marcoedenhofer.edenbank.application.customeraccountservice;

import de.elyesnasri.lieferdienst.lieferdienstelyesnasri.application.postIdentService.VerifyPostIdent;
import de.elyesnasri.lieferdienst.lieferdienstelyesnasri.persistence.entities.PersonalData;
import de.elyesnasri.lieferdienst.lieferdienstelyesnasri.persistence.entities.enums.Salutation;
import de.fatiharslan.bigbazar.service.GivawayService.GiveawayData;
import de.marcoedenhofer.edenbank.application.bankaccountservice.BankAccountService;
import de.marcoedenhofer.edenbank.application.transactionservice.BankTransactionException;
import de.marcoedenhofer.edenbank.application.transactionservice.ITransactionService;
import de.marcoedenhofer.edenbank.application.transactionservice.TransactionData;
import de.marcoedenhofer.edenbank.persistence.entities.*;
import de.marcoedenhofer.edenbank.persistence.repositories.ICustomerAccountRepository;
import de.marcoedenhofer.edenbank.persistence.repositories.ICustomerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Scope;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

@Service
@Qualifier("security")
@Scope("singleton")
public class CustomerAccountService implements ICustomerAccountService {
    private final long EDENBANK_ACCOUNT_ID = 1;
    private final double PRIVATE_MANAGEMENT_FEE = 0.0;
    private final double BUSINESS_MANAGEMENT_FEE = 150;

    // constants for partner api calls
    private final String POST_IDENT_API_URL = "http://im-codd:8808/apis/postIdent/verifyPostIdent";
    private final String GIVEAWAY_API_URL = "http://im-codd:1111/restapi/giveaway/execute";
    private final String GIVEAWAY_SENDER_EMAIL = "edenbank@edenbank.com";

    private final ICustomerRepository customerRepository;
    private final ICustomerAccountRepository customerAccountRepository;
    private final ITransactionService transactionService;
    private final BCryptPasswordEncoder encoder;
    private final RestTemplate restServiceClient;

    @Autowired
    public CustomerAccountService(ICustomerRepository customerRepository,
                                  ICustomerAccountRepository customerAccountRepository,
                                  BCryptPasswordEncoder encoder,
                                  RestTemplate restServiceClient,
                                  ITransactionService transactionService) {
        this.customerRepository = customerRepository;
        this.customerAccountRepository = customerAccountRepository;
        this.encoder = encoder;
        this.restServiceClient = restServiceClient;
        this.transactionService = transactionService;
    }

    @Override
    @Transactional
    public CustomerAccount createPrivateCustomerAccount(PrivateCustomer customer)
            throws PostIdentException {
        if (!customerIsIdentifiedViaPostIdent(customer)) {
            throw new PostIdentException(customer.getPersonalData().getFormOfAddress() + " "
                    + customer.getPersonalData().getFirstname() + " "
                    + customer.getPersonalData().getLastname() +
                    " konnte nicht durch unseren Partner identifiziert werden"
            );
        }
        customer.setIdentified(true);
        customer = customerRepository.save(customer);
        CustomerAccount account = buildCustomerAccount(PRIVATE_MANAGEMENT_FEE, customer);
        return customerAccountRepository.save(account);
    }

    @Override
    @Transactional
    public CustomerAccount createBusinessCustomerAccount(BusinessCustomer customer) {
        customer = customerRepository.save(customer);

        CustomerAccount account = buildCustomerAccount(BUSINESS_MANAGEMENT_FEE, customer);
        return customerAccountRepository.save(account);
    }

    @Override
    public void callGiveawayService(CustomerAccount customerAccount) throws GiveawayException {
        // TODO call fatih for giveaway
        Customer customerData = customerAccount.getCustomerDetails();
        GiveawayData giveawayData = new GiveawayData();

        giveawayData.setCountry(customerData.getPersonalData().getPersonalAddress().getCountry());
        giveawayData.setStreetName(customerData.getPersonalData().getPersonalAddress().getStreetName());
        giveawayData.setHouseNumber(customerData.getPersonalData().getPersonalAddress().getHouseNumber());
        giveawayData.setPostalNumber(customerData.getPersonalData().getPersonalAddress().getPostalNumber().toString());
        final String customerName = customerData.getPersonalData().getFormOfAddress()
                + customerData.getPersonalData().getLastname() + customerData.getPersonalData().getFirstname();
        giveawayData.setName(customerName);
        giveawayData.setSenderEmail(GIVEAWAY_SENDER_EMAIL);
        final int amountOfPresents = 3;
        giveawayData.setAmount(amountOfPresents);

        // returns
        restServiceClient.postForEntity(GIVEAWAY_API_URL, giveawayData, GiveawayData.class);

        boolean giveawayServiceFailed = true;
        if (giveawayServiceFailed) {
            throw new GiveawayException("Es tut uns leid, unser Partnerservice für Geschenke ist ausgefallen. " +
                    "Sie erhalten Ihr Geschenk sobald er wieder verfügbar ist!");
        } else {
            TransactionData transactionData = new TransactionData();
            transactionData.setSenderIban(BankAccountService.EDENBANK_IBAN);
            transactionData.setReceiverIban(BankAccountService.BIGBAZAR_IBAN);
            transactionData.setUsageDetails("Zahlung für Giveaway Service für Kunde: " + customerName);

            try {
                transactionService.requestInternalTransaction(transactionData);
            } catch (BankTransactionException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public CustomerAccount loadCustomerAccountWithId(long customerAccountId) throws UsernameNotFoundException {
        return loadCustomerAccountWithId(String.valueOf(customerAccountId));
    }

    @Override
    public CustomerAccount loadCustomerAccountWithId(String customerAccountId) throws UsernameNotFoundException {
        UserDetails userDetails = loadUserByUsername(customerAccountId);

        if (!(userDetails instanceof CustomerAccount)) {
            throw new UsernameNotFoundException("Account mit Nummer " + customerAccountId + " ist kein Kundenaccount");
        }

        return (CustomerAccount) userDetails;
    }

    @Override
    public UserDetails loadUserByUsername(String customerAccountId) throws UsernameNotFoundException {
        return customerAccountRepository.findById(Long.parseLong(customerAccountId))
                .orElseThrow( () -> {
                    throw new UsernameNotFoundException("Kundenaccount mit Nummer: " + customerAccountId + " existiert nicht");
                });
    }

    @Override
    public boolean customerAccountOwnsBankAccountWithId(CustomerAccount customerAccount, long bankAccountId) {
        for (BankAccount bankAccount : customerAccount.getBankAccounts()) {
            if (bankAccount.getBankAccountId() == bankAccountId) {
                return true;
            }
        }

        return false;
    }

    private CustomerAccount buildCustomerAccount(double managementFee, Customer customer) {
        CustomerAccount account = new CustomerAccount();
        account.setArchived(false);
        // for now use firstname as password
        String password = customer.getPersonalData().getFirstname();
        account.setPassword(encoder.encode(password));
        account.setManagementFee(managementFee);
        account.setCustomerDetails(customer);

        return account;
    }

    public boolean customerIsIdentifiedViaPostIdent(Customer customer) {
        // TODO: call elyes for postident
        VerifyPostIdent postIdentData = new VerifyPostIdent();
        PersonalData personalData = new PersonalData();
        personalData.setBirthDate(customer.getPersonalData().getBirthdate());
        personalData.setBirthPlace(customer.getPersonalData().getBirthPlace());
        personalData.setFirstName(customer.getPersonalData().getFirstname());
        personalData.setLastName(customer.getPersonalData().getLastname());
        personalData.setSalutation(getSalutation(customer));
        postIdentData.setPersonalData(personalData);

        ResponseEntity<Boolean> response = restServiceClient.postForEntity(POST_IDENT_API_URL, postIdentData, Boolean.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            return false;
        }
        return response.getBody().booleanValue();
    }

    private Salutation getSalutation(Customer customer) {
        String salutation = customer.getPersonalData().getFormOfAddress();

        if (salutation.equalsIgnoreCase("Herr")) {
            return Salutation.MAN;
        } else if (salutation.equalsIgnoreCase("Frau")) {
            return Salutation.MAN;
        } else {
            return Salutation.ANY;
        }
    }

    // TODO: implement management fee booking
    private void bookManagementFee() {
        customerAccountRepository.findById(EDENBANK_ACCOUNT_ID).ifPresent(edenbankAccount -> {
            if (edenbankAccount.getBankAccounts().isEmpty()) {
                return;
            } else {
                BankAccount edenbankCheckingAccount = edenbankAccount.getBankAccounts().get(0);
                customerAccountRepository.findAllByCustomerAccountIdNot(EDENBANK_ACCOUNT_ID)
                        .forEach(customerAccount -> {

                        });
            }
        });
    }

}
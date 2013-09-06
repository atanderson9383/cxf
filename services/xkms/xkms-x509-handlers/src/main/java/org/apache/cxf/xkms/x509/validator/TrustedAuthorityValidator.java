/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.xkms.x509.validator;

import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertStore;
import java.security.cert.CertStoreParameters;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.xkms.handlers.Validator;
import org.apache.cxf.xkms.model.xkms.KeyBindingEnum;
import org.apache.cxf.xkms.model.xkms.ReasonEnum;
import org.apache.cxf.xkms.model.xkms.StatusType;
import org.apache.cxf.xkms.model.xkms.ValidateRequestType;
import org.apache.cxf.xkms.x509.repo.CertificateRepo;

public class TrustedAuthorityValidator implements Validator {

    private static final Logger LOG = LogUtils.getL7dLogger(TrustedAuthorityValidator.class);

    CertificateRepo certRepo;
    
    public TrustedAuthorityValidator(CertificateRepo certRepo) {
        this.certRepo = certRepo;
    }

    /**
     * Checks if a certificate is signed by a trusted authority.
     * 
     * @param x509Certificate to check
     * @return the validity state of the certificate
     */
    boolean isCertificateChainValid(List<X509Certificate> certificates) {
        X509Certificate targetCert = certificates.get(0);
        X509CertSelector selector = new X509CertSelector();
        selector.setCertificate(targetCert);
        try {
            List<X509Certificate> intermediateCerts = certRepo.getCaCerts();
            List<X509Certificate> trustedAuthorityCerts = certRepo.getTrustedCaCerts();
            List<X509CRL> crls = certRepo.getCRLs();
            Set<TrustAnchor> trustAnchors = asTrustAnchors(trustedAuthorityCerts);
            CertStoreParameters intermediateParams = new CollectionCertStoreParameters(intermediateCerts);
            CertStoreParameters certificateParams = new CollectionCertStoreParameters(certificates);
            CertStoreParameters crlParams = new CollectionCertStoreParameters(crls);
            PKIXBuilderParameters pkixParams = new PKIXBuilderParameters(trustAnchors, selector);
            pkixParams.addCertStore(CertStore.getInstance("Collection", intermediateParams));
            pkixParams.addCertStore(CertStore.getInstance("Collection", certificateParams));
            pkixParams.addCertStore(CertStore.getInstance("Collection", crlParams));
            if (crls.isEmpty()) {
                pkixParams.setRevocationEnabled(false);
            }
            CertPathBuilder builder = CertPathBuilder.getInstance("PKIX");
            builder.build(pkixParams);
        } catch (InvalidAlgorithmParameterException e) {
            throw new RuntimeException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        } catch (CertPathBuilderException e) {
            LOG.log(Level.INFO, e.getMessage(), e);
            return false;
        }
        return true;
    }

    private Set<TrustAnchor> asTrustAnchors(List<X509Certificate> trustedAuthorityCerts) {
        Set<TrustAnchor> trustAnchors = new HashSet<TrustAnchor>();
        for (X509Certificate trustedAuthorityCert : trustedAuthorityCerts) {
            trustAnchors.add(new TrustAnchor(trustedAuthorityCert, null));
        }
        return trustAnchors;
    }

    @Override
    public StatusType validate(ValidateRequestType request) {
        StatusType status = new StatusType();
        List<X509Certificate> certificates = ValidateRequestParser.parse(request);
        if (certificates == null || certificates.isEmpty()) {
            status.setStatusValue(KeyBindingEnum.HTTP_WWW_W_3_ORG_2002_03_XKMS_INDETERMINATE);
            status.getIndeterminateReason().add("http://www.cxf.apache.org/2002/03/xkms#RequestNotSupported");
        }
        if (isCertificateChainValid(certificates)) {
            status.getValidReason().add(ReasonEnum.HTTP_WWW_W_3_ORG_2002_03_XKMS_ISSUER_TRUST.value());
            status.setStatusValue(KeyBindingEnum.HTTP_WWW_W_3_ORG_2002_03_XKMS_VALID);
        } else {
            status.getInvalidReason().add(ReasonEnum.HTTP_WWW_W_3_ORG_2002_03_XKMS_ISSUER_TRUST.value());
            status.setStatusValue(KeyBindingEnum.HTTP_WWW_W_3_ORG_2002_03_XKMS_INVALID);
        }
        return status;
    }

}

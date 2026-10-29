package com.globalpayments.library.utilities;

import com.tsys.payments.library.domain.CardholderInteractionRequest;
import com.tsys.payments.library.domain.TerminalInfo;

public class ConvertUtil {
      public static com.tsys.payments.library.domain.CardholderInteractionResult MapCardHolderInteractionResult(com.globalpayments.library.terminals.entities.CardholderInteractionResult inResult) {
          final com.tsys.payments.library.domain.CardholderInteractionResult outResult = new com.tsys.payments.library.domain.CardholderInteractionResult(inResult.getCardholderInteractionType());
          outResult.setCommercialCardData(inResult.getCommercialCardData());
          outResult.setFinalAmountConfirmed(inResult.getFinalAmountConfirmed());
          outResult.setFinalTaxAmount(inResult.getFinalTaxAmount());
          outResult.setFinalSurchargeAmount(inResult.getFinalSurchargeAmount());
          outResult.setContinueHostProcessingConfirmed(inResult.getContinueHostProcessingConfirmed());
          outResult.setSelectedAidIndex(inResult.getSelectedAidIndex());
          outResult.setFinalAmount(inResult.getFinalAmount());
          return outResult;
      }

      public static com.globalpayments.library.terminals.entities.CardholderInteractionRequest MapCardHolderInteractionRequest(CardholderInteractionRequest inRequest) {
          final com.globalpayments.library.terminals.entities.CardholderInteractionRequest outRequest = new com.globalpayments.library.terminals.entities.CardholderInteractionRequest();
          outRequest.setCardholderInteractionType(inRequest.getCardholderInteractionType());
          outRequest.setCommercialCardDataFields(inRequest.getCommercialCardDataFields());
          outRequest.setFinalTransactionAmount(inRequest.getFinalTransactionAmount());
          outRequest.setSurchargeAmount(inRequest.getFinalSurchargeAmount());
          outRequest.setIsPreTaxAmount(inRequest.getIsPreTaxAmount());
          outRequest.setCardDataSourceType(inRequest.getCardDataSourceType());
          outRequest.setSupportedApplications(inRequest.getSupportedApplications());
          return outRequest;
      }

      public static com.globalpayments.library.terminals.entities.TerminalInfo MapTerminalInfo(TerminalInfo inInfo) {
          com.globalpayments.library.terminals.entities.TerminalInfo outInfo = new com.globalpayments.library.terminals.entities.TerminalInfo();
          outInfo.setAppName(inInfo.getAppName());
          outInfo.setAppVersion(inInfo.getAppVersion());
          outInfo.setBatteryLevel(inInfo.getBatteryLevel());
          outInfo.setManufacturer(inInfo.getManufacturer());
          outInfo.setModel(inInfo.getModel());
          outInfo.setFirmwareVersion(inInfo.getFirmwareVersion());
          outInfo.setKernelVersion(inInfo.getKernelVersion());
          outInfo.setTerminalType(inInfo.getTerminalType());
          outInfo.setSerialNumber(inInfo.getSerialNumber());
          return outInfo;
      }

      public static com.globalpayments.library.terminals.enums.ErrorType MapErrorType(com.tsys.payments.library.enums.ErrorType inType) {
          return com.globalpayments.library.terminals.enums.ErrorType.values()[inType.ordinal()];
      }
}

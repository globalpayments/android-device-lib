package com.globalpayments.library;

import com.globalpayments.library.entities.Token;

public interface CardFragmentInteractionListener {
    void onTokenSuccess(Token response);
    void onTokenFailure(String response);
}

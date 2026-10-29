package com.globalpayments.library.utilities;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import com.globalpayments.library.BuildConfig;
import com.globalpayments.library.terminals.entities.TerminalResponse;
import com.globalpayments.library.terminals.enums.EntryMode;
import java.text.SimpleDateFormat;
import java.util.Date;

public class ReceiptHelper {

    private static final float FONT_SIZE = 20f;
    private static final float FONT_SIZE_LARGE = 26f;
    private static final float TEXT_MARGIN = 30f;
    private static final String SDK_NAME = "android-device-lib";

    private static int sInvoiceNumber = 0;

    public static Bitmap createReceiptImage(TerminalResponse transaction) {
        return createReceiptImage(transaction, true);
    }

    public static Bitmap createReceiptImage(TerminalResponse transaction, boolean isMerchantReceipt) {
        int width = 550;
        int height = 850;
        float halfWidth = width / 2.f;
        sInvoiceNumber++;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint();
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPaint(paint);

        paint.setColor(Color.BLACK);
        paint.setAntiAlias(true);
        paint.setTextSize(FONT_SIZE);
        paint.setTextAlign(Paint.Align.CENTER);

        //draw the header
        paint.setTextSize(FONT_SIZE_LARGE);
        canvas.drawText("HPS Test", halfWidth, TEXT_MARGIN, paint);
        paint.setTextSize(FONT_SIZE);
        canvas.drawText("1 Heartland Way", halfWidth, TEXT_MARGIN + FONT_SIZE, paint);
        canvas.drawText("Jeffersonville, IN 47136", halfWidth, TEXT_MARGIN + FONT_SIZE * 2, paint);
        canvas.drawText("888-798-3133", halfWidth, TEXT_MARGIN + FONT_SIZE * 3, paint);

        //draw date and time
        paint.setTextAlign(Align.LEFT);
        SimpleDateFormat format = new SimpleDateFormat("MM/dd/yy");
        String date;
        if (transaction.getRspDT() != null) {
            date = format.format(transaction.getRspDT());
        } else {
            date = format.format(new Date());
        }
        canvas.drawText(date, TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 5, paint);
        paint.setTextAlign(Align.RIGHT);
        format = new SimpleDateFormat("hh:mm aa");
        String time = format.format(new Date());
        canvas.drawText(time, width - TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 5, paint);

        //draw transaction type
        paint.setTextSize(FONT_SIZE_LARGE);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("CREDIT CARD", halfWidth, TEXT_MARGIN + FONT_SIZE * 6, paint);
        drawTextSafe(canvas, transaction.getTransactionType(), halfWidth, TEXT_MARGIN + FONT_SIZE * 7, paint);

        //draw detail list
        paint.setTextSize(FONT_SIZE);
        paint.setTextAlign(Paint.Align.LEFT);

        if (transaction.getEntryMode() != EntryMode.SWIPE && transaction.getEntryMode() != EntryMode.CHIP_FALLBACK_SWIPE
                && transaction.getEntryMode() != EntryMode.NONE) {
            canvas.drawText(transaction.getCardType(), TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 9, paint);
            canvas.drawText("ACCT:", TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 10, paint);
            canvas.drawText("APP NAME:", TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 11, paint);
            canvas.drawText("AID:", TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 12, paint);
            canvas.drawText("ARQC:", TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 13, paint);
            canvas.drawText("ENTRY MODE:", TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 14, paint);
            canvas.drawText("ENTRY LEGEND:", TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 15, paint);
            canvas.drawText("APPROVAL:", TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 16, paint);
            canvas.drawText("TXN ID:", TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 17, paint);
            canvas.drawText("INVOICE #:", TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 18, paint);
            canvas.drawText("TVR:", TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 19, paint);

            String maskedCardNumber = transaction.getMaskedCardNumber();
            maskedCardNumber = "xxxx" + maskedCardNumber.substring(4);
            canvas.drawText(maskedCardNumber, 200, TEXT_MARGIN + FONT_SIZE * 10, paint);
            drawTextSafe(canvas, transaction.getApplicationName(), 200, TEXT_MARGIN + FONT_SIZE * 11, paint);
            drawTextSafe(canvas, transaction.getApplicationId(), 200, TEXT_MARGIN + FONT_SIZE * 12, paint);
            drawTextSafe(canvas, transaction.getApplicationCryptogram(), 200, TEXT_MARGIN + FONT_SIZE * 13, paint);
            drawTextSafe(canvas, getEntryMode(transaction.getEntryMode()), 200, TEXT_MARGIN + FONT_SIZE * 14, paint);
            canvas.drawText("CHIP READ", 200, TEXT_MARGIN + FONT_SIZE * 15, paint);
            drawTextSafe(canvas, transaction.getApprovalCode(), 200, TEXT_MARGIN + FONT_SIZE * 16, paint);
            drawTextSafe(canvas, transaction.getTransactionId(), 200, TEXT_MARGIN + FONT_SIZE * 17, paint);
            drawTextSafe(canvas, getFormattedInvoiceNumber(), 200, TEXT_MARGIN + FONT_SIZE * 18, paint);
            drawTextSafe(canvas, transaction.getTerminalVerificationResult(), 200, TEXT_MARGIN + FONT_SIZE * 19, paint);

        } else if (transaction.getEntryMode() == EntryMode.SWIPE || transaction.getEntryMode() == EntryMode.CHIP_FALLBACK_SWIPE) {
            drawTextSafe(canvas, transaction.getCardType(), TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 9, paint);
            canvas.drawText("ACCT:", TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 10, paint);
            canvas.drawText("APP NAME:", TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 11, paint);
            canvas.drawText("ENTRY MODE:", TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 12, paint);
            canvas.drawText("ENTRY LEGEND:", TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 13, paint);
            canvas.drawText("APPROVAL:", TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 14, paint);
            canvas.drawText("TXN ID:", TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 15, paint);
            canvas.drawText("INVOICE #:", TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 16, paint);
            canvas.drawText("TVR:", TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 17, paint);

            drawTextSafe(canvas, transaction.getMaskedCardNumber(), 200, TEXT_MARGIN + FONT_SIZE * 10, paint);
            canvas.drawText("US CREDIT", 200, TEXT_MARGIN + FONT_SIZE * 11, paint);
            drawTextSafe(canvas, getEntryMode(transaction.getEntryMode()), 200, TEXT_MARGIN + FONT_SIZE * 12, paint);
            canvas.drawText("MAG STRIPE", 200, TEXT_MARGIN + FONT_SIZE * 13, paint);
            drawTextSafe(canvas, transaction.getApprovalCode(), 200, TEXT_MARGIN + FONT_SIZE * 14, paint);
            drawTextSafe(canvas, transaction.getTransactionId(), 200, TEXT_MARGIN + FONT_SIZE * 15, paint);
            drawTextSafe(canvas, getFormattedInvoiceNumber(), 200, TEXT_MARGIN + FONT_SIZE * 16, paint);
            drawTextSafe(canvas, transaction.getTerminalVerificationResult(), 200, TEXT_MARGIN + FONT_SIZE * 17, paint);

        } else {
            canvas.drawText("APPROVAL:", TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 9, paint);
            drawTextSafe(canvas, transaction.getApprovalCode(), 200, TEXT_MARGIN + FONT_SIZE * 9, paint);
        }

        //draw description
        canvas.drawText("DESCRIPTION: Merchandise", TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 20, paint);

        //draw total
        paint.setTextSize(FONT_SIZE_LARGE);
        canvas.drawText("TOTAL", TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 22, paint);
        paint.setTextAlign(Align.RIGHT);
        canvas.drawText("USD $ " + transaction.getApprovedAmount(), width - TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 22, paint);

        if (transaction.getEntryMode() != EntryMode.NONE) {
            if ((transaction.getEntryMode() == EntryMode.SWIPE || transaction.getEntryMode() == EntryMode.CHIP_FALLBACK_SWIPE)
                    || (transaction.getCardType().equalsIgnoreCase("DISCOVER") &&
                    (transaction.getCardHolderVerificationMethod() == null || !transaction.getCardHolderVerificationMethod().equals("PIN_VERIFIED")))) {
                //draw agreement
                paint.setTextSize(FONT_SIZE);
                paint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText("I agree to pay above total amount according to card", TEXT_MARGIN,
                        TEXT_MARGIN + FONT_SIZE * 24, paint);
                canvas.drawText("issuer agreement.", TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 25, paint);

                //draw signature area
                if (transaction.getSignatureStatus() == null
                        || (transaction.getSignatureStatus() != null
                        && transaction.getSignatureStatus().equalsIgnoreCase("true"))) {
                    paint.setFlags(Paint.UNDERLINE_TEXT_FLAG);
                    canvas.drawText(
                            "X                                                                                              ",
                            TEXT_MARGIN, TEXT_MARGIN + FONT_SIZE * 28, paint);
                    paint.setFlags(paint.getFlags() & (~Paint.UNDERLINE_TEXT_FLAG));
                    paint.setTextAlign(Paint.Align.CENTER);
                    canvas.drawText("SIGNATURE", halfWidth, TEXT_MARGIN + FONT_SIZE * 29, paint);
                }
            }

            paint.setTextSize(FONT_SIZE);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("No Refunds", halfWidth, TEXT_MARGIN + FONT_SIZE * 31, paint);
            canvas.drawText("Store Credit Only", halfWidth, TEXT_MARGIN + FONT_SIZE * 32, paint);
            canvas.drawText(isMerchantReceipt ? "Merchant Copy" : "Customer Copy", halfWidth,
                    TEXT_MARGIN + FONT_SIZE * 34, paint);
        }

        //draw status
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(FONT_SIZE_LARGE);
        drawTextSafe(canvas, transaction.getDeviceResponseCode(), halfWidth,
                TEXT_MARGIN + FONT_SIZE * 36, paint);
        paint.setTextSize(FONT_SIZE);
        canvas.drawText(SDK_NAME, halfWidth, TEXT_MARGIN + FONT_SIZE * 38, paint);
        canvas.drawText("Version " + BuildConfig.VERSION_NAME, halfWidth,
                TEXT_MARGIN + FONT_SIZE * 39, paint);

        return bitmap;
    }

    private static String getEntryMode(EntryMode entryMode) {
        return entryMode == EntryMode.CHIP ? "CONTACT" : entryMode.toString();
    }

    private static String getFormattedInvoiceNumber() {
        return String.format("%04d", sInvoiceNumber);
    }

    /**
     * Draws text on the canvas only if the text is not null.
     *
     * @param canvas The canvas on which to draw the text.
     * @param text   The text to draw.
     * @param x      The x-coordinate of the text's starting point.
     * @param y      The y-coordinate of the text's baseline.
     * @param paint  The paint used to draw the text.
     */
    private static void drawTextSafe(Canvas canvas, String text, float x, float y, Paint paint) {
        if (text != null)
            canvas.drawText(text, x, y, paint);
    }
}
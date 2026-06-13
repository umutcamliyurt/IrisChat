package com.umut.irischat;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.MotionEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.app.Dialog;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.BitmapTransitionOptions;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.RequestOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.text.SimpleDateFormat;
import java.util.Date;

public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnDeleteListener { void onDelete(int position); }
    public interface OnReplyListener  { void onReply(ChatMessage msg); }
    public interface OnDmListener     { void onDm(String nick); }

    private static final int VIEW_SENT     = 0;
    private static final int VIEW_RECEIVED = 1;
    private static final int VIEW_SYSTEM   = 2;

    private static final Pattern MARKDOWN_LINK =
            Pattern.compile("\\[([^\\]]+)\\]\\(<?(https?://[^)>]+)>?\\)");

    private static final float NORMAL_TEXT_SIZE_SP = 15f;

    private static final float EMOJI_TEXT_SIZE_SP = 32f;

    private static final int EMOJI_ONLY_MAX_COUNT = 6;

    private static final ThreadLocal<SimpleDateFormat> TIME_FMT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm", Locale.getDefault()));

    private final List<ChatMessage> messages;
    private OnDeleteListener deleteListener;
    private OnReplyListener  replyListener;
    private OnDmListener     dmListener;

    public ChatAdapter(List<ChatMessage> messages) { this.messages = messages; }

    public void setOnDeleteListener(OnDeleteListener l) { this.deleteListener = l; }
    public void setOnReplyListener(OnReplyListener l)   { this.replyListener  = l; }
    public void setOnDmListener(OnDmListener l)         { this.dmListener     = l; }

    @Override
    public int getItemViewType(int position) {
        ChatMessage m = messages.get(position);
        if (m.isSystem())  return VIEW_SYSTEM;
        return m.isSent() ? VIEW_SENT : VIEW_RECEIVED;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_SENT) {
            return new SentHolder(inf.inflate(R.layout.item_bubble_sent, parent, false));
        } else if (viewType == VIEW_SYSTEM) {
            return new SystemHolder(inf.inflate(R.layout.item_system_message, parent, false));
        } else {
            return new ReceivedHolder(inf.inflate(R.layout.item_bubble_received, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ChatMessage msg = messages.get(position);
        String text = msg.getText() != null ? msg.getText() : "";

        if (holder instanceof SystemHolder) {
            ((SystemHolder) holder).text.setText(text);
            return;
        }

        if (holder instanceof SentHolder) {
            SentHolder sh = (SentHolder) holder;
            ThemeHelper.tintSentBubble(sh.bubble);
            sh.text.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
            renderLinks(sh.text, text);
            applyEmojiTextSize(sh.text, text);
            bindReplyBanner(sh.replyBanner, sh.replyNick, sh.replyText, msg);
            bindImage(sh.messageImage, msg);
            bindLockBadge(sh.lockBadge, msg.isEncrypted());
            bindTimestamp(sh.timestamp, msg);
            sh.itemView.setOnClickListener(v -> showMessageMenu(sh.bubble, holder, msg, android.view.Gravity.END));
            if (sh.messageImage != null)
                sh.messageImage.setOnClickListener(v -> openFullscreenImage(v, msg.getImageUrl()));

        } else {
            ReceivedHolder rh = (ReceivedHolder) holder;
            ThemeHelper.tintReceivedBubble(rh.nick, rh.replyBanner, rh.replyNick);
            rh.nick.setText(msg.getNick());
            rh.text.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
            renderLinks(rh.text, text);
            applyEmojiTextSize(rh.text, text);
            bindReplyBanner(rh.replyBanner, rh.replyNick, rh.replyText, msg);
            bindImage(rh.messageImage, msg);
            bindLockBadge(rh.lockBadge, msg.isEncrypted());
            bindTimestamp(rh.timestamp, msg);
            rh.itemView.setOnClickListener(v -> showMessageMenu(rh.bubble, holder, msg, android.view.Gravity.START));
            if (rh.messageImage != null)
                rh.messageImage.setOnClickListener(v -> openFullscreenImage(v, msg.getImageUrl()));
        }
    }

    private static void applyEmojiTextSize(TextView tv, String text) {
        float size = isEmojiOnly(text) ? EMOJI_TEXT_SIZE_SP : NORMAL_TEXT_SIZE_SP;
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size);
    }

    private void showMessageMenu(@NonNull View anchor,
                                 @NonNull RecyclerView.ViewHolder holder,
                                 @NonNull ChatMessage msg, int gravity) {
        int pos = holder.getAdapterPosition();
        if (pos == RecyclerView.NO_ID) return;

        String text = msg.getText() != null ? msg.getText() : "";
        boolean hasText = !text.isEmpty();

        boolean isReceived = !msg.isSent();
        String senderNick = msg.getNick();

        android.widget.PopupMenu popup = new android.widget.PopupMenu(
                anchor.getContext(), anchor, gravity);
        popup.getMenu().add(0, 0, 0, anchor.getContext().getString(R.string.reply));
        if (hasText) popup.getMenu().add(0, 1, 1, anchor.getContext().getString(R.string.copy));
        if (isReceived && senderNick != null && !senderNick.isEmpty())
            popup.getMenu().add(0, 2, 2, anchor.getContext().getString(R.string.dm));
        if (msg.isEncrypted() && msg.getRawWire() != null)
            popup.getMenu().add(0, 4, 4, anchor.getContext().getString(R.string.show_raw));
        popup.getMenu().add(0, 3, 3, anchor.getContext().getString(R.string.delete));

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 0) {
                if (replyListener != null) replyListener.onReply(messages.get(pos));
            } else if (id == 1 && hasText) {
                ClipboardManager cm = (ClipboardManager)
                        anchor.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText(anchor.getContext().getString(R.string.copy), text));
                    Toast.makeText(anchor.getContext(), R.string.copied, Toast.LENGTH_SHORT).show();
                }
            } else if (id == 2 && isReceived) {
                if (dmListener != null) dmListener.onDm(senderNick);
            } else if (id == 4) {
                showRawDialog(anchor.getContext(), msg.getRawWire());
            } else if (id == 3) {
                if (deleteListener != null) deleteListener.onDelete(pos);
            }
            return true;
        });
        popup.show();
    }

    private static void showRawDialog(Context ctx, String raw) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
        android.widget.TextView tv = new android.widget.TextView(ctx);
        int pad = (int)(12 * ctx.getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        tv.setText(raw);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        scroll.addView(tv);

        new androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.raw_ciphertext)
                .setView(scroll)
                .setPositiveButton(R.string.copy, (d, w) -> {
                    ClipboardManager cm = (ClipboardManager)
                            ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null)
                        cm.setPrimaryClip(ClipData.newPlainText(ctx.getString(R.string.raw_clipboard_label), raw));
                    Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.close_dialog, null)
                .show();
    }

    private void openFullscreenImage(@NonNull View anchor, String url) {
        if (url == null) return;

        if (!isSafeImageUrl(url)) {
            android.util.Log.w("ChatAdapter", "openFullscreenImage: blocked non-http(s) URL");
            return;
        }

        Context ctx = anchor.getContext();

        Dialog dialog = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));

        ImageView iv = new ImageView(ctx);
        iv.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(iv);

        if (url.toLowerCase().contains(".gif")) {
            Glide.with(ctx).asGif().load(url).into(iv);
        } else {
            Glide.with(ctx).asBitmap().load(url)
                    .apply(new RequestOptions().fitCenter())
                    .into(iv);
        }

        dialog.show();
    }

    private static boolean isSafeImageUrl(String url) {
        if (url == null) return false;
        String lc = url.toLowerCase(java.util.Locale.ROOT);
        return lc.startsWith("https://") || lc.startsWith("http://");
    }

    private static boolean isEmojiOnly(String text) {
        if (text == null || text.isEmpty()) return false;

        int emojiCount = 0;
        int i = 0;
        int len = text.length();
        boolean sawNonWhitespace = false;

        while (i < len) {
            int cp = text.codePointAt(i);
            int cpLen = Character.charCount(cp);

            if (Character.isWhitespace(cp)) {
                i += cpLen;
                continue;
            }
            sawNonWhitespace = true;

            if (isEmojiModifierOrJoiner(cp)) {
                i += cpLen;
                continue;
            }

            if (!isEmojiCodePoint(cp)) {
                return false;
            }

            emojiCount++;
            if (emojiCount > EMOJI_ONLY_MAX_COUNT) return false;
            i += cpLen;
        }

        return sawNonWhitespace && emojiCount > 0;
    }

    private static boolean isEmojiModifierOrJoiner(int cp) {
        return cp == 0x200D
                || cp == 0xFE0F
                || cp == 0xFE0E
                || (cp >= 0x1F3FB && cp <= 0x1F3FF);
    }

    private static boolean isEmojiCodePoint(int cp) {
        return (cp >= 0x1F300 && cp <= 0x1FAFF)
                || (cp >= 0x1F1E6 && cp <= 0x1F1FF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x2300 && cp <= 0x23FF)
                || (cp >= 0x2B00 && cp <= 0x2BFF)
                || cp == 0x2764
                || cp == 0x303D
                || cp == 0x3030;
    }

    private static void renderLinks(TextView tv, String raw) {
        if (raw == null || raw.isEmpty()) {
            tv.setText("");
            tv.setMovementMethod(null);
            return;
        }

        Matcher m = MARKDOWN_LINK.matcher(raw);
        if (!m.find()) {
            tv.setText(raw);
            tv.setMovementMethod(null);
            return;
        }

        StringBuilder display   = new StringBuilder();
        List<int[]>   positions = new ArrayList<>();
        List<String>  urls      = new ArrayList<>();

        int last = 0;
        m.reset();
        while (m.find()) {
            display.append(raw, last, m.start());
            int spanStart = display.length();
            display.append(m.group(1));

            String linkUrl = m.group(2);

            if (isSafeImageUrl(linkUrl)) {
                positions.add(new int[]{spanStart, display.length()});
                urls.add(linkUrl);
            }

            last = m.end();
        }
        display.append(raw, last, raw.length());

        SpannableString ss = new SpannableString(display);
        for (int i = 0; i < positions.size(); i++) {
            final String url = urls.get(i);
            ss.setSpan(new ClickableSpan() {
                @Override
                public void onClick(@NonNull View widget) {
                    try {
                        Uri uri = Uri.parse(url);
                        String scheme = uri.getScheme();
                        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
                            return;
                        }
                        widget.getContext().startActivity(
                                new Intent(Intent.ACTION_VIEW, uri));
                    } catch (Exception ignored) {}
                }
                @Override
                public void updateDrawState(@NonNull TextPaint ds) {
                    ds.setColor(0xFF4FC3F7);
                    ds.setUnderlineText(true);
                    ds.bgColor = 0x00000000;
                }
            }, positions.get(i)[0], positions.get(i)[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        tv.setText(ss);
        tv.setMovementMethod(new LinkMovementMethod() {
            @Override
            public boolean onTouchEvent(@NonNull android.widget.TextView widget,
                                        @NonNull android.text.Spannable buffer,
                                        @NonNull MotionEvent event) {
                int action = event.getAction();
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_DOWN) {
                    int x = (int) event.getX() - widget.getTotalPaddingLeft() + widget.getScrollX();
                    int y = (int) event.getY() - widget.getTotalPaddingTop()  + widget.getScrollY();
                    android.text.Layout layout = widget.getLayout();
                    if (layout != null) {
                        int line   = layout.getLineForVertical(y);
                        int offset = layout.getOffsetForHorizontal(line, x);
                        ClickableSpan[] spans = buffer.getSpans(
                                offset, offset, ClickableSpan.class);
                        if (spans.length > 0) {
                            return super.onTouchEvent(widget, buffer, event);
                        }
                    }
                }
                return false;
            }
        });
        tv.setHighlightColor(0x334FC3F7);
    }

    private void bindImage(ImageView imageView, ChatMessage msg) {
        if (imageView == null) return;
        String url = msg.getImageUrl();
        if (url != null && isSafeImageUrl(url)) {
            imageView.setVisibility(View.VISIBLE);
            float density = imageView.getContext().getResources().getDisplayMetrics().density;
            int targetPx = Math.round(260 * density);
            RequestOptions opts = new RequestOptions()
                    .override(targetPx, targetPx)
                    .fitCenter();
            String lower = url.toLowerCase();
            if (lower.contains(".gif")) {
                Glide.with(imageView.getContext())
                        .asGif()
                        .load(url)
                        .apply(opts)
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .into(imageView);
            } else {
                Glide.with(imageView.getContext())
                        .asBitmap()
                        .load(url)
                        .apply(opts)
                        .transition(BitmapTransitionOptions.withCrossFade())
                        .into(imageView);
            }
        } else {
            imageView.setVisibility(View.GONE);
            Glide.with(imageView.getContext()).clear(imageView);
        }
    }

    private static void bindTimestamp(TextView tv, ChatMessage msg) {
        if (tv == null) return;
        long ts = msg.getTimestamp();
        if (ts <= 0) {
            tv.setVisibility(View.GONE);
        } else {
            tv.setVisibility(View.VISIBLE);
            tv.setText(TIME_FMT.get().format(new Date(ts)));
        }
    }

    private static void bindLockBadge(ImageView badge, boolean encrypted) {
        if (badge == null) return;
        badge.setVisibility(encrypted ? View.VISIBLE : View.GONE);
    }

    private void bindReplyBanner(LinearLayout banner, TextView nickView,
                                 TextView textView, ChatMessage msg) {
        if (msg.hasReply()) {
            banner.setVisibility(View.VISIBLE);
            nickView.setText(msg.getReplyToNick());
            String replyText = msg.getReplyToText() != null ? msg.getReplyToText() : "";
            String imgUrl = MainActivity.extractImageUrl(replyText);
            if (imgUrl != null) replyText = replyText.replace(imgUrl, "").trim();
            if (replyText.isEmpty()) replyText = textView.getContext().getString(R.string.image_placeholder);
            textView.setText(replyText);
        } else {
            banner.setVisibility(View.GONE);
        }
    }

    public void deleteAt(int position) {
        messages.remove(position);
        notifyItemRemoved(position);
    }

    @Override
    public int getItemCount() { return messages.size(); }

    static class SystemHolder extends RecyclerView.ViewHolder {
        TextView text;
        SystemHolder(View v) {
            super(v);
            text = v.findViewById(R.id.systemMessageText);
        }
    }

    static class SentHolder extends RecyclerView.ViewHolder {
        View          bubble;
        TextView      text;
        TextView      timestamp;
        LinearLayout  replyBanner;
        TextView      replyNick, replyText;
        ImageView     messageImage;
        ImageView     lockBadge;
        SentHolder(View v) {
            super(v);
            bubble       = v.findViewById(R.id.bubble);
            text         = v.findViewById(R.id.bubbleText);
            timestamp    = v.findViewById(R.id.bubbleTimestamp);
            replyBanner  = v.findViewById(R.id.replyBanner);
            replyNick    = v.findViewById(R.id.replyNick);
            replyText    = v.findViewById(R.id.replyText);
            messageImage = v.findViewById(R.id.messageImage);
            lockBadge    = v.findViewById(R.id.lockBadge);
        }
    }

    static class ReceivedHolder extends RecyclerView.ViewHolder {
        View          bubble;
        TextView      nick, text;
        TextView      timestamp;
        LinearLayout  replyBanner;
        TextView      replyNick, replyText;
        ImageView     messageImage;
        ImageView     lockBadge;
        ReceivedHolder(View v) {
            super(v);
            bubble       = v.findViewById(R.id.bubble);
            nick         = v.findViewById(R.id.bubbleNick);
            text         = v.findViewById(R.id.bubbleText);
            timestamp    = v.findViewById(R.id.bubbleTimestamp);
            replyBanner  = v.findViewById(R.id.replyBanner);
            replyNick    = v.findViewById(R.id.replyNick);
            replyText    = v.findViewById(R.id.replyText);
            messageImage = v.findViewById(R.id.messageImage);
            lockBadge    = v.findViewById(R.id.lockBadge);
        }
    }
}
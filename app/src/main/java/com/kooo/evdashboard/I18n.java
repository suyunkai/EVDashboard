package com.kooo.evdashboard;

import android.content.Context;
import android.content.res.Configuration;
import android.widget.TextView;
import android.widget.Button;
import com.kooo.evdashboard.core.*;
import java.io.*;
import java.util.Locale;

/** Central display boundary, including asynchronous status/error updates. */
final class I18n {
    private static volatile Translations translations;
    static boolean english(Context c) {return c.getSharedPreferences("settings", 0).getString("uiLanguage", "zh").equals("en");}
    static Context localized(Context base) {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(english(base) ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE);
        return base.createConfigurationContext(config);
    }
    private static Translations catalog(Context c) {
        if (translations == null) synchronized (I18n.class) {
            if (translations == null) try (InputStream in = c.getAssets().open("i18n/en.json")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(); FilesEx.transfer(in, out);
                translations = new Translations(out.toString("UTF-8"));
            } catch (Exception error) {throw new IllegalStateException("Cannot load translations", error);}
        }
        return translations;
    }
    static CharSequence text(Context c, CharSequence value) {
        if (value == null) return null;
        String s = value.toString();
        if (!english(c)) return s.indexOf(Translations.LITERAL_START) >= 0 ? Translations.unmark(s) : value;
        boolean han = false; for (int i = 0; i < s.length(); i++) if (Character.UnicodeScript.of(s.charAt(i)) == Character.UnicodeScript.HAN) {han = true; break;}
        return han || s.indexOf(Translations.LITERAL_START) >= 0 ? catalog(c).translate(s) : value;
    }
    static String string(Context c, String value) {return text(c, value).toString();}
    static String literal(String value) {return Translations.literal(value);}
    static final class Label extends TextView {
        Label(Context c) {super(c);}
        @Override public void setText(CharSequence text, BufferType type) {super.setText(I18n.text(getContext(), text), type);}
        @Override public void setContentDescription(CharSequence text) {super.setContentDescription(I18n.text(getContext(), text));}
    }
    static final class Action extends Button {
        Action(Context c) {super(c);}
        @Override public void setText(CharSequence text, BufferType type) {super.setText(I18n.text(getContext(), text), type);}
        @Override public void setContentDescription(CharSequence text) {super.setContentDescription(I18n.text(getContext(), text));}
    }
}

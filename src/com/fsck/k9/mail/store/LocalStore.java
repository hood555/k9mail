
package com.fsck.k9.mail.store;

import java.io.*;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;

import org.apache.commons.io.IOUtils;

import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.text.Html;
import android.util.Log;

import com.fsck.k9.Account;
import com.fsck.k9.K9;
import com.fsck.k9.Preferences;
import com.fsck.k9.controller.MessageRemovalListener;
import com.fsck.k9.controller.MessageRetrievalListener;
import com.fsck.k9.helper.Regex;
import com.fsck.k9.helper.Utility;
import com.fsck.k9.mail.Address;
import com.fsck.k9.mail.Body;
import com.fsck.k9.mail.BodyPart;
import com.fsck.k9.mail.FetchProfile;
import com.fsck.k9.mail.Flag;
import com.fsck.k9.mail.Folder;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.Message.RecipientType;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.Part;
import com.fsck.k9.mail.Store;
import com.fsck.k9.mail.filter.Base64OutputStream;
import com.fsck.k9.mail.internet.MimeBodyPart;
import com.fsck.k9.mail.internet.MimeHeader;
import com.fsck.k9.mail.internet.MimeMessage;
import com.fsck.k9.mail.internet.MimeMultipart;
import com.fsck.k9.mail.internet.MimeUtility;
import com.fsck.k9.mail.internet.TextBody;
import com.fsck.k9.mail.store.LockableDatabase.DbCallback;
import com.fsck.k9.mail.store.LockableDatabase.WrappedException;
import com.fsck.k9.mail.store.StorageManager.StorageProvider;
import com.fsck.k9.provider.AttachmentProvider;

/**
 * <pre>
 * Implements a SQLite database backed local store for Messages.
 * </pre>
 */
public class LocalStore extends Store implements Serializable
{

    private static final Message[] EMPTY_MESSAGE_ARRAY = new Message[0];

    /**
     * Immutable empty {@link String} array
     */
    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    private static final Flag[] PERMANENT_FLAGS = { Flag.DELETED, Flag.X_DESTROYED, Flag.SEEN, Flag.FLAGGED };

    private static final int MAX_SMART_HTMLIFY_MESSAGE_LENGTH = 1024 * 256 ;

    private static Set<String> HEADERS_TO_SAVE = new HashSet<String>();
    static
    {
        HEADERS_TO_SAVE.add(K9.K9MAIL_IDENTITY);
        HEADERS_TO_SAVE.add("To");
        HEADERS_TO_SAVE.add("Cc");
        HEADERS_TO_SAVE.add("From");
        HEADERS_TO_SAVE.add("In-Reply-To");
        HEADERS_TO_SAVE.add("References");
        HEADERS_TO_SAVE.add("Content-ID");
        HEADERS_TO_SAVE.add("Content-Disposition");
        HEADERS_TO_SAVE.add("X-User-Agent");
    }
    /*
     * a String containing the columns getMessages expects to work with
     * in the correct order.
     */
    static private String GET_MESSAGES_COLS =
        "subject, sender_list, date, uid, flags, id, to_list, cc_list, "
        + "bcc_list, reply_to_list, attachment_count, internal_date, message_id, folder_id, preview ";

    /**
     * When generating previews, Spannable objects that can't be converted into a String are
     * represented as 0xfffc. When displayed, these show up as undisplayed squares. These constants
     * define the object character and the replacement character.
     */
    private static final char PREVIEW_OBJECT_CHARACTER = (char)0xfffc;
    private static final char PREVIEW_OBJECT_REPLACEMENT = (char)0x20;  // space

    protected static final int DB_VERSION = 39;

    protected String uUid = null;

    private final Application mApplication;

    private LockableDatabase database;

    /**
     * local://localhost/path/to/database/uuid.db
     * This constructor is only used by {@link Store#getLocalInstance(Account, Application)}
     * @param account
     * @param application
     * @throws UnavailableStorageException if not {@link StorageProvider#isReady(Context)}
     */
    public LocalStore(final Account account, final Application application) throws MessagingException
    {
        super(account);
        database = new LockableDatabase(application, account.getUuid(), new StoreSchemaDefinition());

        mApplication = application;
        database.setStorageProviderId(account.getLocalStorageProviderId());
        uUid = account.getUuid();

        database.open();
    }

    public void switchLocalStorage(final String newStorageProviderId) throws MessagingException
    {
        database.switchProvider(newStorageProviderId);
    }

    private class StoreSchemaDefinition implements LockableDatabase.SchemaDefinition
    {
        @Override
        public int getVersion()
        {
            return DB_VERSION;
        }

        @Override
        public void doDbUpgrade(final SQLiteDatabase db)
        {
            Log.i(K9.LOG_TAG, String.format("Upgrading database from version %d to version %d",
                                            db.getVersion(), DB_VERSION));


            AttachmentProvider.clear(mApplication);

            try
            {
                // schema version 29 was when we moved to incremental updates
                // in the case of a new db or a < v29 db, we blow away and start from scratch
                if (db.getVersion() < 29)
                {

                    db.execSQL("DROP TABLE IF EXISTS folders");
                    db.execSQL("CREATE TABLE folders (id INTEGER PRIMARY KEY, name TEXT, "
                               + "last_updated INTEGER, unread_count INTEGER, visible_limit INTEGER, status TEXT, push_state TEXT, last_pushed INTEGER, flagged_count INTEGER default 0)");

                    db.execSQL("CREATE INDEX IF NOT EXISTS folder_name ON folders (name)");
                    db.execSQL("DROP TABLE IF EXISTS messages");
                    db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY, deleted INTEGER default 0, folder_id INTEGER, uid TEXT, subject TEXT, "
                               + "date INTEGER, flags TEXT, sender_list TEXT, to_list TEXT, cc_list TEXT, bcc_list TEXT, reply_to_list TEXT, "
                               + "html_content TEXT, text_content TEXT, attachment_count INTEGER, internal_date INTEGER, message_id TEXT, preview TEXT)");

                    db.execSQL("DROP TABLE IF EXISTS headers");
                    db.execSQL("CREATE TABLE headers (id INTEGER PRIMARY KEY, message_id INTEGER, name TEXT, value TEXT)");
                    db.execSQL("CREATE INDEX IF NOT EXISTS header_folder ON headers (message_id)");

                    db.execSQL("CREATE INDEX IF NOT EXISTS msg_uid ON messages (uid, folder_id)");
                    db.execSQL("DROP INDEX IF EXISTS msg_folder_id");
                    db.execSQL("DROP INDEX IF EXISTS msg_folder_id_date");
                    db.execSQL("CREATE INDEX IF NOT EXISTS msg_folder_id_deleted_date ON messages (folder_id,deleted,internal_date)");
                    db.execSQL("DROP TABLE IF EXISTS attachments");
                    db.execSQL("CREATE TABLE attachments (id INTEGER PRIMARY KEY, message_id INTEGER,"
                               + "store_data TEXT, content_uri TEXT, size INTEGER, name TEXT,"
                               + "mime_type TEXT, content_id TEXT, content_disposition TEXT)");

                    db.execSQL("DROP TABLE IF EXISTS pending_commands");
                    db.execSQL("CREATE TABLE pending_commands " +
                               "(id INTEGER PRIMARY KEY, command TEXT, arguments TEXT)");

                    db.execSQL("DROP TRIGGER IF EXISTS delete_folder");
                    db.execSQL("CREATE TRIGGER delete_folder BEFORE DELETE ON folders BEGIN DELETE FROM messages WHERE old.id = folder_id; END;");

                    db.execSQL("DROP TRIGGER IF EXISTS delete_message");
                    db.execSQL("CREATE TRIGGER delete_message BEFORE DELETE ON messages BEGIN DELETE FROM attachments WHERE old.id = message_id; "
                               + "DELETE FROM headers where old.id = message_id; END;");
                }
                else
                {
                    // in the case that we're starting out at 29 or newer, run all the needed updates

                    if (db.getVersion() < 30)
                    {
                        try
                        {
                            db.execSQL("ALTER TABLE messages ADD deleted INTEGER default 0");
                        }
                        catch (SQLiteException e)
                        {
                            if (! e.toString().startsWith("duplicate column name: deleted"))
                            {
                                throw e;
                            }
                        }
                    }
                    if (db.getVersion() < 31)
                    {
                        db.execSQL("DROP INDEX IF EXISTS msg_folder_id_date");
                        db.execSQL("CREATE INDEX IF NOT EXISTS msg_folder_id_deleted_date ON messages (folder_id,deleted,internal_date)");
                    }
                    if (db.getVersion() < 32)
                    {
                        db.execSQL("UPDATE messages SET deleted = 1 WHERE flags LIKE '%DELETED%'");
                    }
                    if (db.getVersion() < 33)
                    {

                        try
                        {
                            db.execSQL("ALTER TABLE messages ADD preview TEXT");
                        }
                        catch (SQLiteException e)
                        {
                            if (! e.toString().startsWith("duplicate column name: preview"))
                            {
                                throw e;
                            }
                        }

                    }
                    if (db.getVersion() < 34)
                    {
                        try
                        {
                            db.execSQL("ALTER TABLE folders ADD flagged_count INTEGER default 0");
                        }
                        catch (SQLiteException e)
                        {
                            if (! e.getMessage().startsWith("duplicate column name: flagged_count"))
                            {
                                throw e;
                            }
                        }
                    }
                    if (db.getVersion() < 35)
                    {
                        try
                        {
                            db.execSQL("update messages set flags = replace(flags, 'X_NO_SEEN_INFO', 'X_BAD_FLAG')");
                        }
                        catch (SQLiteException e)
                        {
                            Log.e(K9.LOG_TAG, "Unable to get rid of obsolete flag X_NO_SEEN_INFO", e);
                        }
                    }
                    if (db.getVersion() < 36)
                    {
                        try
                        {
                            db.execSQL("ALTER TABLE attachments ADD content_id TEXT");
                        }
                        catch (SQLiteException e)
                        {
                            Log.e(K9.LOG_TAG, "Unable to add content_id column to attachments");
                        }
                    }
                    if (db.getVersion() < 37)
                    {
                        try
                        {
                            db.execSQL("ALTER TABLE attachments ADD content_disposition TEXT");
                        }
                        catch (SQLiteException e)
                        {
                            Log.e(K9.LOG_TAG, "Unable to add content_disposition column to attachments");
                        }
                    }


                    // Database version 38 is solely to prune cached attachments now that we clear them better
                    if (db.getVersion() < 39)
                    {
                        try
                        {
                            db.execSQL("DELETE FROM headers WHERE id in (SELECT headers.id FROM headers LEFT JOIN messages ON headers.message_id = messages.id WHERE messages.id IS NULL)");
                        }
                        catch (SQLiteException e)
                        {
                            Log.e(K9.LOG_TAG, "Unable to remove extra header data from the database");
                        }
                    }



                }

            }
            catch (SQLiteException e)
            {
                Log.e(K9.LOG_TAG, "Exception while upgrading database. Resetting the DB to v0");
                db.setVersion(0);
                throw new Error("Database upgrade failed! Resetting your DB version to 0 to force a full schema recreation.");
            }



            db.setVersion(DB_VERSION);

            if (db.getVersion() != DB_VERSION)
            {
                throw new Error("Database upgrade failed!");
            }

            // Unless we're blowing away the whole data store, there's no reason to prune attachments
            // every time the user upgrades. it'll just cost them money and pain.
            // try
            //{
            //        pruneCachedAttachments(true);
            //}
            //catch (Exception me)
            //{
            //   Log.e(K9.LOG_TAG, "Exception while force pruning attachments during DB update", me);
            //}
        }
    }

    public long getSize() throws UnavailableStorageException
    {

        final StorageManager storageManager = StorageManager.getInstance(mApplication);

        final File attachmentDirectory = storageManager.getAttachmentDirectory(uUid,
                                         database.getStorageProviderId());

        return database.execute(false, new DbCallback<Long>()
        {
            @Override
            public Long doDbWork(final SQLiteDatabase db)
            {
                final File[] files = attachmentDirectory.listFiles();
                long attachmentLength = 0;
                for (File file : files)
                {
                    if (file.exists())
                    {
                        attachmentLength += file.length();
                    }
                }

                final File dbFile = storageManager.getDatabase(uUid, database.getStorageProviderId());
                return dbFile.length() + attachmentLength;
            }
        });
    }

    public void compact() throws MessagingException
    {
        if (K9.DEBUG)
            Log.i(K9.LOG_TAG, "Before compaction size = " + getSize());

        database.execute(false, new DbCallback<Void>()
        {
            @Override
            public Void doDbWork(final SQLiteDatabase db) throws WrappedException
            {
                db.execSQL("VACUUM");
                return null;
            }
        });
        if (K9.DEBUG)
            Log.i(K9.LOG_TAG, "After compaction size = " + getSize());
    }


    public void clear() throws MessagingException
    {
        if (K9.DEBUG)
            Log.i(K9.LOG_TAG, "Before prune size = " + getSize());

        pruneCachedAttachments(true);
        if (K9.DEBUG)
        {
            Log.i(K9.LOG_TAG, "After prune / before compaction size = " + getSize());

            Log.i(K9.LOG_TAG, "Before clear folder count = " + getFolderCount());
            Log.i(K9.LOG_TAG, "Before clear message count = " + getMessageCount());

            Log.i(K9.LOG_TAG, "After prune / before clear size = " + getSize());
        }
        // don't delete messages that are Local, since there is no copy on the server.
        // Don't delete deleted messages.  They are essentially placeholders for UIDs of messages that have
        // been deleted locally.  They take up insignificant space
        database.execute(false, new DbCallback<Void>()
        {
            @Override
            public Void doDbWork(final SQLiteDatabase db)
            {
                db.execSQL("DELETE FROM messages WHERE deleted = 0 and uid not like 'Local%'");
                db.execSQL("update folders set flagged_count = 0, unread_count = 0");
                return null;
            }
        });

        compact();

        if (K9.DEBUG)
        {
            Log.i(K9.LOG_TAG, "After clear message count = " + getMessageCount());

            Log.i(K9.LOG_TAG, "After clear size = " + getSize());
        }
    }

    public int getMessageCount() throws MessagingException
    {
        return database.execute(false, new DbCallback<Integer>()
        {
            @Override
            public Integer doDbWork(final SQLiteDatabase db)
            {
                Cursor cursor = null;
                try
                {
                    cursor = db.rawQuery("SELECT COUNT(*) FROM messages", null);
                    cursor.moveToFirst();
                    return cursor.getInt(0);   // message count



                }
                finally
                {
                    if (cursor != null)
                    {
                        cursor.close();
                    }
                }
            }
        });
    }

    public int getFolderCount() throws MessagingException
    {
        return database.execute(false, new DbCallback<Integer>()
        {
            @Override
            public Integer doDbWork(final SQLiteDatabase db)
            {
                Cursor cursor = null;
                try
                {
                    cursor = db.rawQuery("SELECT COUNT(*) FROM folders", null);
                    cursor.moveToFirst();
                    return cursor.getInt(0);        // folder count
                }
                finally
                {
                    if (cursor != null)
                    {
                        cursor.close();
                    }
                }
            }
        });
    }

    @Override
    public LocalFolder getFolder(String name)
    {
        return new LocalFolder(name);
    }

    // TODO this takes about 260-300ms, seems slow.
    @Override
    public List<? extends Folder> getPersonalNamespaces(boolean forceListAll) throws MessagingException
    {
        final List<LocalFolder> folders = new LinkedList<LocalFolder>();
        try
        {
            database.execute(false, new DbCallback<List<? extends Folder>>()
            {
                @Override
                public List<? extends Folder> doDbWork(final SQLiteDatabase db) throws WrappedException
                {
                    Cursor cursor = null;

                    try
                    {
                        cursor = db.rawQuery("SELECT id, name, unread_count, visible_limit, last_updated, status, push_state, last_pushed, flagged_count FROM folders ORDER BY name ASC", null);
                        while (cursor.moveToNext())
                        {
                            LocalFolder folder = new LocalFolder(cursor.getString(1));
                            folder.open(cursor.getInt(0), cursor.getString(1), cursor.getInt(2), cursor.getInt(3), cursor.getLong(4), cursor.getString(5), cursor.getString(6), cursor.getLong(7), cursor.getInt(8));

                            folders.add(folder);
                        }
                        return folders;
                    }
                    catch (MessagingException e)
                    {
                        throw new WrappedException(e);
                    }
                    finally
                    {
                        if (cursor != null)
                        {
                            cursor.close();
                        }
                    }
                }
            });
        }
        catch (WrappedException e)
        {
            throw (MessagingException) e.getCause();
        }
        return folders;
    }

    @Override
    public void checkSettings() throws MessagingException
    {
    }

    public void delete() throws UnavailableStorageException
    {
        database.delete();
    }

    public void recreate() throws UnavailableStorageException
    {
        database.recreate();
    }

    public void pruneCachedAttachments() throws MessagingException
    {
        pruneCachedAttachments(false);
    }

    /**
     * Deletes all cached attachments for the entire store.
     * @param force
     * @throws com.fsck.k9.mail.MessagingException
     */
    private void pruneCachedAttachments(final boolean force) throws MessagingException
    {
        database.execute(false, new DbCallback<Void>()
        {
            @Override
            public Void doDbWork(final SQLiteDatabase db) throws WrappedException
            {
                if (force)
                {
                    ContentValues cv = new ContentValues();
                    cv.putNull("content_uri");
                    db.update("attachments", cv, null, null);
                }
                final StorageManager storageManager = StorageManager.getInstance(mApplication);
                File[] files = storageManager.getAttachmentDirectory(uUid, database.getStorageProviderId()).listFiles();
                for (File file : files)
                {
                    if (file.exists())
                    {
                        if (!force)
                        {
                            Cursor cursor = null;
                            try
                            {
                                cursor = db.query(
                                             "attachments",
                                             new String[] { "store_data" },
                                             "id = ?",
                                             new String[] { file.getName() },
                                             null,
                                             null,
                                             null);
                                if (cursor.moveToNext())
                                {
                                    if (cursor.getString(0) == null)
                                    {
                                        if (K9.DEBUG)
                                            Log.d(K9.LOG_TAG, "Attachment " + file.getAbsolutePath() + " has no store data, not deleting");
                                        /*
                                         * If the attachment has no store data it is not recoverable, so
                                         * we won't delete it.
                                         */
                                        continue;
                                    }
                                }
                            }
                            finally
                            {
                                if (cursor != null)
                                {
                                    cursor.close();
                                }
                            }
                        }
                        if (!force)
                        {
                            try
                            {
                                ContentValues cv = new ContentValues();
                                cv.putNull("content_uri");
                                db.update("attachments", cv, "id = ?", new String[] { file.getName() });
                            }
                            catch (Exception e)
                            {
                                /*
                                 * If the row has gone away before we got to mark it not-downloaded that's
                                 * okay.
                                 */
                            }
                        }
                        if (!file.delete())
                        {
                            file.deleteOnExit();
                        }
                    }
                }
                return null;
            }
        });
    }

    public void resetVisibleLimits() throws UnavailableStorageException
    {
        resetVisibleLimits(mAccount.getDisplayCount());
    }

    public void resetVisibleLimits(int visibleLimit) throws UnavailableStorageException
    {
        final ContentValues cv = new ContentValues();
        cv.put("visible_limit", Integer.toString(visibleLimit));
        database.execute(false, new DbCallback<Void>()
        {
            @Override
            public Void doDbWork(final SQLiteDatabase db) throws WrappedException
            {
                db.update("folders", cv, null, null);
                return null;
            }
        });
    }

    public ArrayList<PendingCommand> getPendingCommands() throws UnavailableStorageException
    {
        return database.execute(false, new DbCallback<ArrayList<PendingCommand>>()
        {
            @Override
            public ArrayList<PendingCommand> doDbWork(final SQLiteDatabase db) throws WrappedException
            {
                Cursor cursor = null;
                try
                {
                    cursor = db.query("pending_commands",
                                      new String[] { "id", "command", "arguments" },
                                      null,
                                      null,
                                      null,
                                      null,
                                      "id ASC");
                    ArrayList<PendingCommand> commands = new ArrayList<PendingCommand>();
                    while (cursor.moveToNext())
                    {
                        PendingCommand command = new PendingCommand();
                        command.mId = cursor.getLong(0);
                        command.command = cursor.getString(1);
                        String arguments = cursor.getString(2);
                        command.arguments = arguments.split(",");
                        for (int i = 0; i < command.arguments.length; i++)
                        {
                            command.arguments[i] = Utility.fastUrlDecode(command.arguments[i]);
                        }
                        commands.add(command);
                    }
                    return commands;
                }
                finally
                {
                    if (cursor != null)
                    {
                        cursor.close();
                    }
                }
            }
        });
    }

    public void addPendingCommand(PendingCommand command) throws UnavailableStorageException
    {
        try
        {
            for (int i = 0; i < command.arguments.length; i++)
            {
                command.arguments[i] = URLEncoder.encode(command.arguments[i], "UTF-8");
            }
            final ContentValues cv = new ContentValues();
            cv.put("command", command.command);
            cv.put("arguments", Utility.combine(command.arguments, ','));
            database.execute(false, new DbCallback<Void>()
            {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException
                {
                    db.insert("pending_commands", "command", cv);
                    return null;
                }
            });
        }
        catch (UnsupportedEncodingException usee)
        {
            throw new Error("Aparently UTF-8 has been lost to the annals of history.");
        }
    }

    public void removePendingCommand(final PendingCommand command) throws UnavailableStorageException
    {
        database.execute(false, new DbCallback<Void>()
        {
            @Override
            public Void doDbWork(final SQLiteDatabase db) throws WrappedException
            {
                db.delete("pending_commands", "id = ?", new String[] { Long.toString(command.mId) });
                return null;
            }
        });
    }

    public void removePendingCommands() throws UnavailableStorageException
    {
        database.execute(false, new DbCallback<Void>()
        {
            @Override
            public Void doDbWork(final SQLiteDatabase db) throws WrappedException
            {
                db.delete("pending_commands", null, null);
                return null;
            }
        });
    }

    public static class PendingCommand
    {
        private long mId;
        public String command;
        public String[] arguments;

        @Override
        public String toString()
        {
            StringBuffer sb = new StringBuffer();
            sb.append(command);
            sb.append(": ");
            for (String argument : arguments)
            {
                sb.append(", ");
                sb.append(argument);
                //sb.append("\n");
            }
            return sb.toString();
        }
    }

    @Override
    public boolean isMoveCapable()
    {
        return true;
    }

    @Override
    public boolean isCopyCapable()
    {
        return true;
    }

    public Message[] searchForMessages(MessageRetrievalListener listener, String[] queryFields, String queryString,
                                       List<LocalFolder> folders, Message[] messages, final Flag[] requiredFlags, final Flag[] forbiddenFlags) throws MessagingException
    {
        List<String> args = new LinkedList<String>();

        StringBuilder whereClause = new StringBuilder();
        if (queryString != null && queryString.length() > 0)
        {
            boolean anyAdded = false;
            String likeString = "%"+queryString+"%";
            whereClause.append(" AND (");
            for (String queryField : queryFields)
            {

                if (anyAdded)
                {
                    whereClause.append(" OR ");
                }
                whereClause.append(queryField).append(" LIKE ? ");
                args.add(likeString);
                anyAdded = true;
            }


            whereClause.append(" )");
        }
        if (folders != null && folders.size() > 0)
        {
            whereClause.append(" AND folder_id in (");
            boolean anyAdded = false;
            for (LocalFolder folder : folders)
            {
                if (anyAdded)
                {
                    whereClause.append(",");
                }
                anyAdded = true;
                whereClause.append("?");
                args.add(Long.toString(folder.getId()));
            }
            whereClause.append(" )");
        }
        if (messages != null && messages.length > 0)
        {
            whereClause.append(" AND ( ");
            boolean anyAdded = false;
            for (Message message : messages)
            {
                if (anyAdded)
                {
                    whereClause.append(" OR ");
                }
                anyAdded = true;
                whereClause.append(" ( uid = ? AND folder_id = ? ) ");
                args.add(message.getUid());
                args.add(Long.toString(((LocalFolder)message.getFolder()).getId()));
            }
            whereClause.append(" )");
        }
        if (forbiddenFlags != null && forbiddenFlags.length > 0)
        {
            whereClause.append(" AND (");
            boolean anyAdded = false;
            for (Flag flag : forbiddenFlags)
            {
                if (anyAdded)
                {
                    whereClause.append(" AND ");
                }
                anyAdded = true;
                whereClause.append(" flags NOT LIKE ?");

                args.add("%" + flag.toString() + "%");
            }
            whereClause.append(" )");
        }
        if (requiredFlags != null && requiredFlags.length > 0)
        {
            whereClause.append(" AND (");
            boolean anyAdded = false;
            for (Flag flag : requiredFlags)
            {
                if (anyAdded)
                {
                    whereClause.append(" OR ");
                }
                anyAdded = true;
                whereClause.append(" flags LIKE ?");

                args.add("%" + flag.toString() + "%");
            }
            whereClause.append(" )");
        }

        if (K9.DEBUG)
        {
            Log.v(K9.LOG_TAG, "whereClause = " + whereClause.toString());
            Log.v(K9.LOG_TAG, "args = " + args);
        }
        return getMessages(
                   listener,
                   null,
                   "SELECT "
                   + GET_MESSAGES_COLS
                   + "FROM messages WHERE deleted = 0 " + whereClause.toString() + " ORDER BY date DESC"
                   , args.toArray(EMPTY_STRING_ARRAY)
               );
    }
    /*
     * Given a query string, actually do the query for the messages and
     * call the MessageRetrievalListener for each one
     */
    private Message[] getMessages(
        final MessageRetrievalListener listener,
        final LocalFolder folder,
        final String queryString, final String[] placeHolders
    ) throws MessagingException
    {
        final ArrayList<LocalMessage> messages = new ArrayList<LocalMessage>();
        final int j = database.execute(false, new DbCallback<Integer>()
        {
            @Override
            public Integer doDbWork(final SQLiteDatabase db) throws WrappedException
            {
                Cursor cursor = null;
                int i = 0;
                try
                {
                    cursor = db.rawQuery(queryString + " LIMIT 10", placeHolders);

                    while (cursor.moveToNext())
                    {
                        LocalMessage message = new LocalMessage(null, folder);
                        message.populateFromGetMessageCursor(cursor);

                        messages.add(message);
                        if (listener != null)
                        {
                            listener.messageFinished(message, i, -1);
                        }
                        i++;
                    }
                    cursor.close();
                    cursor = db.rawQuery(queryString + " LIMIT -1 OFFSET 10", placeHolders);

                    while (cursor.moveToNext())
                    {
                        LocalMessage message = new LocalMessage(null, folder);
                        message.populateFromGetMessageCursor(cursor);

                        messages.add(message);
                        if (listener != null)
                        {
                            listener.messageFinished(message, i, -1);
                        }
                        i++;
                    }
                }
                catch (Exception e)
                {
                    Log.d(K9.LOG_TAG,"Got an exception "+e);
                }
                finally
                {
                    if (cursor != null)
                    {
                        cursor.close();
                    }
                }
                return i;
            }
        });
        if (listener != null)
        {
            listener.messagesFinished(j);
        }

        return messages.toArray(EMPTY_MESSAGE_ARRAY);

    }

    public String getAttachmentType(final String attachmentId) throws UnavailableStorageException
    {
        return database.execute(false, new DbCallback<String>()
        {
            @Override
            public String doDbWork(final SQLiteDatabase db) throws WrappedException
            {
                Cursor cursor = null;
                try
                {
                    cursor = db.query(
                                 "attachments",
                                 new String[] { "mime_type", "name" },
                                 "id = ?",
                                 new String[] { attachmentId },
                                 null,
                                 null,
                                 null);
                    cursor.moveToFirst();
                    String type = cursor.getString(0);
                    String name = cursor.getString(1);
                    cursor.close();

                    if (MimeUtility.DEFAULT_ATTACHMENT_MIME_TYPE.equals(type))
                    {
                        type = MimeUtility.getMimeTypeByExtension(name);
                    }
                    return type;
                }
                finally
                {
                    if (cursor != null)
                    {
                        cursor.close();
                    }
                }
            }
        });
    }

    public AttachmentInfo getAttachmentInfo(final String attachmentId) throws UnavailableStorageException
    {
        return database.execute(false, new DbCallback<AttachmentInfo>()
        {
            @Override
            public AttachmentInfo doDbWork(final SQLiteDatabase db) throws WrappedException
            {
                String name;
                int size;
                Cursor cursor = null;
                try
                {
                    cursor = db.query(
                                 "attachments",
                                 new String[] { "name", "size" },
                                 "id = ?",
                                 new String[] { attachmentId },
                                 null,
                                 null,
                                 null);
                    if (!cursor.moveToFirst())
                    {
                        return null;
                    }
                    name = cursor.getString(0);
                    size = cursor.getInt(1);
                    final AttachmentInfo attachmentInfo = new AttachmentInfo();
                    attachmentInfo.name = name;
                    attachmentInfo.size = size;
                    return attachmentInfo;
                }
                finally
                {
                    if (cursor != null)
                    {
                        cursor.close();
                    }
                }
            }
        });
    }

    public static class AttachmentInfo
    {
        public String name;
        public int size;
    }

    public class LocalFolder extends Folder implements Serializable
    {
        private String mName = null;
        private long mFolderId = -1;
        private int mUnreadMessageCount = -1;
        private int mFlaggedMessageCount = -1;
        private int mVisibleLimit = -1;
        private FolderClass displayClass = FolderClass.NO_CLASS;
        private FolderClass syncClass = FolderClass.INHERITED;
        private FolderClass pushClass = FolderClass.SECOND_CLASS;
        private boolean inTopGroup = false;
        private String prefId = null;
        private String mPushState = null;
        private boolean mIntegrate = false;
        // mLastUid is used during syncs. It holds the highest UID within the local folder so we
        // know whether or not an unread message added to the local folder is actually "new" or not.
        private Integer mLastUid = null;

        public LocalFolder(String name)
        {
            super(LocalStore.this.mAccount);
            this.mName = name;

            if (K9.INBOX.equals(getName()))
            {
                syncClass =  FolderClass.FIRST_CLASS;
                pushClass =  FolderClass.FIRST_CLASS;
                inTopGroup = true;
            }


        }

        public LocalFolder(long id)
        {
            super(LocalStore.this.mAccount);
            this.mFolderId = id;
        }

        public long getId()
        {
            return mFolderId;
        }

        @Override
        public void open(final OpenMode mode) throws MessagingException
        {
            if (isOpen())
            {
                return;
            }
            try
            {
                database.execute(false, new DbCallback<Void>()
                {
                    @Override
                    public Void doDbWork(final SQLiteDatabase db) throws WrappedException
                    {
                        Cursor cursor = null;
                        try
                        {
                            String baseQuery =
                                "SELECT id, name,unread_count, visible_limit, last_updated, status, push_state, last_pushed, flagged_count FROM folders ";
                            if (mName != null)
                            {
                                cursor = db.rawQuery(baseQuery + "where folders.name = ?", new String[] { mName });
                            }
                            else
                            {
                                cursor = db.rawQuery(baseQuery + "where folders.id = ?", new String[] { Long.toString(mFolderId) });
                            }

                            if (cursor.moveToFirst())
                            {
                                int folderId = cursor.getInt(0);
                                if (folderId > 0)
                                {
                                    open(folderId, cursor.getString(1), cursor.getInt(2), cursor.getInt(3), cursor.getLong(4), cursor.getString(5), cursor.getString(6), cursor.getLong(7), cursor.getInt(8));
                                }
                            }
                            else
                            {
                                Log.w(K9.LOG_TAG, "Creating folder " + getName() + " with existing id " + getId());
                                create(FolderType.HOLDS_MESSAGES);
                                open(mode);
                            }
                        }
                        catch (MessagingException e)
                        {
                            throw new WrappedException(e);
                        }
                        finally
                        {
                            if (cursor != null)
                            {
                                cursor.close();
                            }
                        }
                        return null;
                    }
                });
            }
            catch (WrappedException e)
            {
                throw (MessagingException) e.getCause();
            }
        }

        private void open(int id, String name, int unreadCount, int visibleLimit, long lastChecked, String status, String pushState, long lastPushed, int flaggedCount) throws MessagingException
        {
            mFolderId = id;
            mName = name;
            mUnreadMessageCount = unreadCount;
            mVisibleLimit = visibleLimit;
            mPushState = pushState;
            mFlaggedMessageCount = flaggedCount;
            super.setStatus(status);
            // Only want to set the local variable stored in the super class.  This class
            // does a DB update on setLastChecked
            super.setLastChecked(lastChecked);
            super.setLastPush(lastPushed);
        }

        @Override
        public boolean isOpen()
        {
            return (mFolderId != -1 && mName != null);
        }

        @Override
        public OpenMode getMode()
        {
            return OpenMode.READ_WRITE;
        }

        @Override
        public String getName()
        {
            return mName;
        }

        @Override
        public boolean exists() throws MessagingException
        {
            return database.execute(false, new DbCallback<Boolean>()
            {
                @Override
                public Boolean doDbWork(final SQLiteDatabase db) throws WrappedException
                {
                    Cursor cursor = null;
                    try
                    {
                        cursor = db.rawQuery("SELECT id FROM folders "
                                             + "where folders.name = ?", new String[] { LocalFolder.this
                                                     .getName()
                                                                                      });
                        if (cursor.moveToFirst())
                        {
                            int folderId = cursor.getInt(0);
                            return (folderId > 0);
                        }
                        else
                        {
                            return false;
                        }
                    }
                    finally
                    {
                        if (cursor != null)
                        {
                            cursor.close();
                        }
                    }
                }
            });
        }

        @Override
        public boolean create(FolderType type) throws MessagingException
        {
            if (exists())
            {
                throw new MessagingException("Folder " + mName + " already exists.");
            }
            database.execute(false, new DbCallback<Void>()
            {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException
                {
                    db.execSQL("INSERT INTO folders (name, visible_limit) VALUES (?, ?)", new Object[]
                               {
                                   mName,
                                   mAccount.getDisplayCount()
                               });
                    return null;
                }
            });
            return true;
        }

        @Override
        public boolean create(FolderType type, final int visibleLimit) throws MessagingException
        {
            if (exists())
            {
                throw new MessagingException("Folder " + mName + " already exists.");
            }
            database.execute(false, new DbCallback<Void>()
            {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException
                {
                    db.execSQL("INSERT INTO folders (name, visible_limit) VALUES (?, ?)", new Object[]
                               {
                                   mName,
                                   visibleLimit
                               });
                    return null;
                }
            });
            return true;
        }

        @Override
        public void close()
        {
            mFolderId = -1;
        }

        @Override
        public int getMessageCount() throws MessagingException
        {
            try
            {
                return database.execute(false, new DbCallback<Integer>()
                {
                    @Override
                    public Integer doDbWork(final SQLiteDatabase db) throws WrappedException
                    {
                        try
                        {
                            open(OpenMode.READ_WRITE);
                        }
                        catch (MessagingException e)
                        {
                            throw new WrappedException(e);
                        }
                        Cursor cursor = null;
                        try
                        {
                            cursor = db.rawQuery("SELECT COUNT(*) FROM messages WHERE folder_id = ?",
                                                 new String[]
                                                 {
                                                     Long.toString(mFolderId)
                                                 });
                            cursor.moveToFirst();
                            return cursor.getInt(0);   //messagecount
                        }
                        finally
                        {
                            if (cursor != null)
                            {
                                cursor.close();
                            }
                        }
                    }
                });
            }
            catch (WrappedException e)
            {
                throw (MessagingException) e.getCause();
            }
        }

        @Override
        public int getUnreadMessageCount() throws MessagingException
        {
            open(OpenMode.READ_WRITE);
            return mUnreadMessageCount;
        }

        @Override
        public int getFlaggedMessageCount() throws MessagingException
        {
            open(OpenMode.READ_WRITE);
            return mFlaggedMessageCount;
        }

        public void setUnreadMessageCount(final int unreadMessageCount) throws MessagingException
        {
            try
            {
                database.execute(false, new DbCallback<Void>()
                {
                    @Override
                    public Void doDbWork(final SQLiteDatabase db) throws WrappedException
                    {
                        try
                        {
                            open(OpenMode.READ_WRITE);
                        }
                        catch (MessagingException e)
                        {
                            throw new WrappedException(e);
                        }
                        mUnreadMessageCount = Math.max(0, unreadMessageCount);
                        db.execSQL("UPDATE folders SET unread_count = ? WHERE id = ?",
                                   new Object[] { mUnreadMessageCount, mFolderId });
                        return null;
                    }
                });
            }
            catch (WrappedException e)
            {
                throw (MessagingException) e.getCause();
            }
        }

        public void setFlaggedMessageCount(final int flaggedMessageCount) throws MessagingException
        {
            try
            {
                database.execute(false, new DbCallback<Integer>()
                {
                    @Override
                    public Integer doDbWork(final SQLiteDatabase db) throws WrappedException
                    {
                        try
                        {
                            open(OpenMode.READ_WRITE);
                        }
                        catch (MessagingException e)
                        {
                            throw new WrappedException(e);
                        }
                        mFlaggedMessageCount = Math.max(0, flaggedMessageCount);
                        db.execSQL("UPDATE folders SET flagged_count = ? WHERE id = ?", new Object[]
                                   { mFlaggedMessageCount, mFolderId });
                        return null;
                    }
                });
            }
            catch (WrappedException e)
            {
                throw (MessagingException) e.getCause();
            }
        }

        @Override
        public void setLastChecked(final long lastChecked) throws MessagingException
        {
            try
            {
                database.execute(false, new DbCallback<Void>()
                {
                    @Override
                    public Void doDbWork(final SQLiteDatabase db) throws WrappedException
                    {
                        try
                        {
                            open(OpenMode.READ_WRITE);
                            LocalFolder.super.setLastChecked(lastChecked);
                        }
                        catch (MessagingException e)
                        {
                            throw new WrappedException(e);
                        }
                        db.execSQL("UPDATE folders SET last_updated = ? WHERE id = ?", new Object[]
                                   { lastChecked, mFolderId });
                        return null;
                    }
                });
            }
            catch (WrappedException e)
            {
                throw (MessagingException) e.getCause();
            }
        }

        @Override
        public void setLastPush(final long lastChecked) throws MessagingException
        {
            try
            {
                database.execute(false, new DbCallback<Void>()
                {
                    @Override
                    public Void doDbWork(final SQLiteDatabase db) throws WrappedException
                    {
                        try
                        {
                            open(OpenMode.READ_WRITE);
                            LocalFolder.super.setLastPush(lastChecked);
                        }
                        catch (MessagingException e)
                        {
                            throw new WrappedException(e);
                        }
                        db.execSQL("UPDATE folders SET last_pushed = ? WHERE id = ?", new Object[]
                                   { lastChecked, mFolderId });
                        return null;
                    }
                });
            }
            catch (WrappedException e)
            {
                throw (MessagingException) e.getCause();
            }
        }

        public int getVisibleLimit() throws MessagingException
        {
            open(OpenMode.READ_WRITE);
            return mVisibleLimit;
        }

        public void purgeToVisibleLimit(MessageRemovalListener listener) throws MessagingException
        {
            if ( mVisibleLimit == 0)
            {
                return ;
            }
            open(OpenMode.READ_WRITE);
            Message[] messages = getMessages(null, false);
            for (int i = mVisibleLimit; i < messages.length; i++)
            {
                if (listener != null)
                {
                    listener.messageRemoved(messages[i]);
                }
                messages[i].destroy();

            }
        }


        public void setVisibleLimit(final int visibleLimit) throws MessagingException
        {
            database.execute(false, new DbCallback<Void>()
            {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException
                {
                    try
                    {
                        open(OpenMode.READ_WRITE);
                    }
                    catch (MessagingException e)
                    {
                        throw new WrappedException(e);
                    }
                    mVisibleLimit = visibleLimit;
                    db.execSQL("UPDATE folders SET visible_limit = ? WHERE id = ?",
                               new Object[] { mVisibleLimit, mFolderId });
                    return null;
                }
            });
        }

        @Override
        public void setStatus(final String status) throws MessagingException
        {
            try
            {
                database.execute(false, new DbCallback<Void>()
                {
                    @Override
                    public Void doDbWork(final SQLiteDatabase db) throws WrappedException
                    {
                        try
                        {
                            open(OpenMode.READ_WRITE);
                            LocalFolder.super.setStatus(status);
                        }
                        catch (MessagingException e)
                        {
                            throw new WrappedException(e);
                        }
                        db.execSQL("UPDATE folders SET status = ? WHERE id = ?", new Object[]
                                   { status, mFolderId });
                        return null;
                    }
                });
            }
            catch (WrappedException e)
            {
                throw (MessagingException) e.getCause();
            }
        }
        public void setPushState(final String pushState) throws MessagingException
        {
            try
            {
                database.execute(false, new DbCallback<Void>()
                {
                    @Override
                    public Void doDbWork(final SQLiteDatabase db) throws WrappedException
                    {
                        try
                        {
                            open(OpenMode.READ_WRITE);
                        }
                        catch (MessagingException e)
                        {
                            throw new WrappedException(e);
                        }
                        mPushState = pushState;
                        db.execSQL("UPDATE folders SET push_state = ? WHERE id = ?", new Object[]
                                   { pushState, mFolderId });
                        return null;
                    }
                });
            }
            catch (WrappedException e)
            {
                throw (MessagingException) e.getCause();
            }
        }
        public String getPushState()
        {
            return mPushState;
        }
        @Override
        public FolderClass getDisplayClass()
        {
            return displayClass;
        }

        @Override
        public FolderClass getSyncClass()
        {
            if (FolderClass.INHERITED == syncClass)
            {
                return getDisplayClass();
            }
            else
            {
                return syncClass;
            }
        }

        public FolderClass getRawSyncClass()
        {
            return syncClass;

        }

        @Override
        public FolderClass getPushClass()
        {
            if (FolderClass.INHERITED == pushClass)
            {
                return getSyncClass();
            }
            else
            {
                return pushClass;
            }
        }

        public FolderClass getRawPushClass()
        {
            return pushClass;

        }

        public void setDisplayClass(FolderClass displayClass)
        {
            this.displayClass = displayClass;
        }

        public void setSyncClass(FolderClass syncClass)
        {
            this.syncClass = syncClass;
        }
        public void setPushClass(FolderClass pushClass)
        {
            this.pushClass = pushClass;
        }

        public boolean isIntegrate()
        {
            return mIntegrate;
        }
        public void setIntegrate(boolean integrate)
        {
            mIntegrate = integrate;
        }

        private String getPrefId() throws MessagingException
        {
            open(OpenMode.READ_WRITE);

            if (prefId == null)
            {
                prefId = uUid + "." + mName;
            }

            return prefId;
        }

        public void delete(Preferences preferences) throws MessagingException
        {
            String id = getPrefId();

            SharedPreferences.Editor editor = preferences.getPreferences().edit();

            editor.remove(id + ".displayMode");
            editor.remove(id + ".syncMode");
            editor.remove(id + ".pushMode");
            editor.remove(id + ".inTopGroup");
            editor.remove(id + ".integrate");

            editor.commit();
        }

        public void save(Preferences preferences) throws MessagingException
        {
            String id = getPrefId();

            SharedPreferences.Editor editor = preferences.getPreferences().edit();
            // there can be a lot of folders.  For the defaults, let's not save prefs, saving space, except for INBOX
            if (displayClass == FolderClass.NO_CLASS && !K9.INBOX.equals(getName()))
            {
                editor.remove(id + ".displayMode");
            }
            else
            {
                editor.putString(id + ".displayMode", displayClass.name());
            }

            if (syncClass == FolderClass.INHERITED && !K9.INBOX.equals(getName()))
            {
                editor.remove(id + ".syncMode");
            }
            else
            {
                editor.putString(id + ".syncMode", syncClass.name());
            }

            if (pushClass == FolderClass.SECOND_CLASS && !K9.INBOX.equals(getName()))
            {
                editor.remove(id + ".pushMode");
            }
            else
            {
                editor.putString(id + ".pushMode", pushClass.name());
            }
            editor.putBoolean(id + ".inTopGroup", inTopGroup);

            editor.putBoolean(id + ".integrate", mIntegrate);

            editor.commit();
        }


        public FolderClass getDisplayClass(Preferences preferences) throws MessagingException
        {
            String id = getPrefId();
            return FolderClass.valueOf(preferences.getPreferences().getString(id + ".displayMode",
                                       FolderClass.NO_CLASS.name()));
        }

        @Override
        public void refresh(Preferences preferences) throws MessagingException
        {

            String id = getPrefId();

            try
            {
                displayClass = FolderClass.valueOf(preferences.getPreferences().getString(id + ".displayMode",
                                                   FolderClass.NO_CLASS.name()));
            }
            catch (Exception e)
            {
                Log.e(K9.LOG_TAG, "Unable to load displayMode for " + getName(), e);

                displayClass = FolderClass.NO_CLASS;
            }
            if (displayClass == FolderClass.NONE)
            {
                displayClass = FolderClass.NO_CLASS;
            }


            FolderClass defSyncClass = FolderClass.INHERITED;
            if (K9.INBOX.equals(getName()))
            {
                defSyncClass =  FolderClass.FIRST_CLASS;
            }

            try
            {
                syncClass = FolderClass.valueOf(preferences.getPreferences().getString(id  + ".syncMode",
                                                defSyncClass.name()));
            }
            catch (Exception e)
            {
                Log.e(K9.LOG_TAG, "Unable to load syncMode for " + getName(), e);

                syncClass = defSyncClass;
            }
            if (syncClass == FolderClass.NONE)
            {
                syncClass = FolderClass.INHERITED;
            }

            FolderClass defPushClass = FolderClass.SECOND_CLASS;
            boolean defInTopGroup = false;
            boolean defIntegrate = false;
            if (K9.INBOX.equals(getName()))
            {
                defPushClass =  FolderClass.FIRST_CLASS;
                defInTopGroup = true;
                defIntegrate = true;
            }

            try
            {
                pushClass = FolderClass.valueOf(preferences.getPreferences().getString(id  + ".pushMode",
                                                defPushClass.name()));
            }
            catch (Exception e)
            {
                Log.e(K9.LOG_TAG, "Unable to load pushMode for " + getName(), e);

                pushClass = defPushClass;
            }
            if (pushClass == FolderClass.NONE)
            {
                pushClass = FolderClass.INHERITED;
            }
            inTopGroup = preferences.getPreferences().getBoolean(id + ".inTopGroup", defInTopGroup);
            mIntegrate = preferences.getPreferences().getBoolean(id + ".integrate", defIntegrate);

        }

        @Override
        public void fetch(final Message[] messages, final FetchProfile fp, final MessageRetrievalListener listener)
        throws MessagingException
        {
            try
            {
                database.execute(false, new DbCallback<Void>()
                {
                    @Override
                    public Void doDbWork(final SQLiteDatabase db) throws WrappedException
                    {
                        try
                        {
                            open(OpenMode.READ_WRITE);
                            if (fp.contains(FetchProfile.Item.BODY))
                            {
                                for (Message message : messages)
                                {
                                    LocalMessage localMessage = (LocalMessage)message;
                                    Cursor cursor = null;
                                    MimeMultipart mp = new MimeMultipart();
                                    mp.setSubType("mixed");
                                    try
                                    {
                                        cursor = db.rawQuery("SELECT html_content, text_content FROM messages "
                                                             + "WHERE id = ?",
                                                             new String[] { Long.toString(localMessage.mId) });
                                        cursor.moveToNext();
                                        String htmlContent = cursor.getString(0);
                                        String textContent = cursor.getString(1);

                                        if (textContent != null)
                                        {
                                            LocalTextBody body = new LocalTextBody(textContent, htmlContent);
                                            MimeBodyPart bp = new MimeBodyPart(body, "text/plain");
                                            mp.addBodyPart(bp);
                                        }
                                        else
                                        {
                                            TextBody body = new TextBody(htmlContent);
                                            MimeBodyPart bp = new MimeBodyPart(body, "text/html");
                                            mp.addBodyPart(bp);
                                        }
                                    }
                                    finally
                                    {
                                        if (cursor != null)
                                        {
                                            cursor.close();
                                        }
                                    }

                                    try
                                    {
                                        cursor = db.query(
                                                     "attachments",
                                                     new String[]
                                                     {
                                                         "id",
                                                         "size",
                                                         "name",
                                                         "mime_type",
                                                         "store_data",
                                                         "content_uri",
                                                         "content_id",
                                                         "content_disposition"
                                                     },
                                                     "message_id = ?",
                                                     new String[] { Long.toString(localMessage.mId) },
                                                     null,
                                                     null,
                                                     null);

                                        while (cursor.moveToNext())
                                        {
                                            long id = cursor.getLong(0);
                                            int size = cursor.getInt(1);
                                            String name = cursor.getString(2);
                                            String type = cursor.getString(3);
                                            String storeData = cursor.getString(4);
                                            String contentUri = cursor.getString(5);
                                            String contentId = cursor.getString(6);
                                            String contentDisposition = cursor.getString(7);
                                            Body body = null;

                                            if (contentDisposition == null)
                                            {
                                                contentDisposition = "attachment";
                                            }

                                            if (contentUri != null)
                                            {
                                                body = new LocalAttachmentBody(Uri.parse(contentUri), mApplication);
                                            }
                                            MimeBodyPart bp = new LocalAttachmentBodyPart(body, id);
                                            bp.setHeader(MimeHeader.HEADER_CONTENT_TYPE,
                                                         String.format("%s;\n name=\"%s\"",
                                                                       type,
                                                                       name));
                                            bp.setHeader(MimeHeader.HEADER_CONTENT_TRANSFER_ENCODING, "base64");
                                            bp.setHeader(MimeHeader.HEADER_CONTENT_DISPOSITION,
                                                         String.format("%s;\n filename=\"%s\";\n size=%d",
                                                                       contentDisposition,
                                                                       name,
                                                                       size));

                                            bp.setHeader(MimeHeader.HEADER_CONTENT_ID, contentId);
                                            /*
                                             * HEADER_ANDROID_ATTACHMENT_STORE_DATA is a custom header we add to that
                                             * we can later pull the attachment from the remote store if neccesary.
                                             */
                                            bp.setHeader(MimeHeader.HEADER_ANDROID_ATTACHMENT_STORE_DATA, storeData);

                                            mp.addBodyPart(bp);
                                        }
                                    }
                                    finally
                                    {
                                        if (cursor != null)
                                        {
                                            cursor.close();
                                        }
                                    }

                                    if (mp.getCount() == 1)
                                    {
                                        BodyPart part = mp.getBodyPart(0);
                                        localMessage.setHeader(MimeHeader.HEADER_CONTENT_TYPE, part.getContentType());
                                        localMessage.setBody(part.getBody());
                                    }
                                    else
                                    {
                                        localMessage.setHeader(MimeHeader.HEADER_CONTENT_TYPE, "multipart/mixed");
                                        localMessage.setBody(mp);
                                    }
                                }
                            }
                        }
                        catch (MessagingException e)
                        {
                            throw new WrappedException(e);
                        }
                        return null;
                    }
                });
            }
            catch (WrappedException e)
            {
                throw (MessagingException) e.getCause();
            }
        }

        @Override
        public Message[] getMessages(int start, int end, Date earliestDate, MessageRetrievalListener listener)
        throws MessagingException
        {
            open(OpenMode.READ_WRITE);
            throw new MessagingException(
                "LocalStore.getMessages(int, int, MessageRetrievalListener) not yet implemented");
        }

        /**
         * Populate the header fields of the given list of messages by reading
         * the saved header data from the database.
         *
         * @param messages
         *            The messages whose headers should be loaded.
         * @throws UnavailableStorageException
         */
        private void populateHeaders(final List<LocalMessage> messages) throws UnavailableStorageException
        {
            database.execute(false, new DbCallback<Void>()
            {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException
                {
                    Cursor cursor = null;
                    if (messages.size() == 0)
                    {
                        return null;
                    }
                    try
                    {
                        Map<Long, LocalMessage> popMessages = new HashMap<Long, LocalMessage>();
                        List<String> ids = new ArrayList<String>();
                        StringBuffer questions = new StringBuffer();

                        for (int i = 0; i < messages.size(); i++)
                        {
                            if (i != 0)
                            {
                                questions.append(", ");
                            }
                            questions.append("?");
                            LocalMessage message = messages.get(i);
                            Long id = message.getId();
                            ids.add(Long.toString(id));
                            popMessages.put(id, message);

                        }

                        cursor = db.rawQuery(
                                     "SELECT message_id, name, value FROM headers " + "WHERE message_id in ( " + questions + ") ",
                                     ids.toArray(EMPTY_STRING_ARRAY));


                        while (cursor.moveToNext())
                        {
                            Long id = cursor.getLong(0);
                            String name = cursor.getString(1);
                            String value = cursor.getString(2);
                            //Log.i(K9.LOG_TAG, "Retrieved header name= " + name + ", value = " + value + " for message " + id);
                            popMessages.get(id).addHeader(name, value);
                        }
                    }
                    finally
                    {
                        if (cursor != null)
                        {
                            cursor.close();
                        }
                    }
                    return null;
                }
            });
        }

        @Override
        public Message getMessage(final String uid) throws MessagingException
        {
            try
            {
                return database.execute(false, new DbCallback<Message>()
                {
                    @Override
                    public Message doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException
                    {
                        try
                        {
                            open(OpenMode.READ_WRITE);
                            LocalMessage message = new LocalMessage(uid, LocalFolder.this);
                            Cursor cursor = null;

                            try
                            {
                                cursor = db.rawQuery(
                                             "SELECT "
                                             + GET_MESSAGES_COLS
                                             + "FROM messages WHERE uid = ? AND folder_id = ?",
                                             new String[]
                                             {
                                                 message.getUid(), Long.toString(mFolderId)
                                             });
                                if (!cursor.moveToNext())
                                {
                                    return null;
                                }
                                message.populateFromGetMessageCursor(cursor);
                            }
                            finally
                            {
                                if (cursor != null)
                                {
                                    cursor.close();
                                }
                            }
                            return message;
                        }
                        catch (MessagingException e)
                        {
                            throw new WrappedException(e);
                        }
                    }
                });
            }
            catch (WrappedException e)
            {
                throw (MessagingException) e.getCause();
            }
        }

        @Override
        public Message[] getMessages(MessageRetrievalListener listener) throws MessagingException
        {
            return getMessages(listener, true);
        }

        @Override
        public Message[] getMessages(final MessageRetrievalListener listener, final boolean includeDeleted) throws MessagingException
        {
            try
            {
                return database.execute(false, new DbCallback<Message[]>()
                {
                    @Override
                    public Message[] doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException
                    {
                        try
                        {
                            open(OpenMode.READ_WRITE);
                            return LocalStore.this.getMessages(
                                       listener,
                                       LocalFolder.this,
                                       "SELECT " + GET_MESSAGES_COLS
                                       + "FROM messages WHERE "
                                       + (includeDeleted ? "" : "deleted = 0 AND ")
                                       + " folder_id = ? ORDER BY date DESC"
                                       , new String[]
                                       {
                                           Long.toString(mFolderId)
                                       }
                                   );
                        }
                        catch (MessagingException e)
                        {
                            throw new WrappedException(e);
                        }
                    }
                });
            }
            catch (WrappedException e)
            {
                throw (MessagingException) e.getCause();
            }
        }


        @Override
        public Message[] getMessages(String[] uids, MessageRetrievalListener listener)
        throws MessagingException
        {
            open(OpenMode.READ_WRITE);
            if (uids == null)
            {
                return getMessages(listener);
            }
            ArrayList<Message> messages = new ArrayList<Message>();
            for (String uid : uids)
            {
                Message message = getMessage(uid);
                if (message != null)
                {
                    messages.add(message);
                }
            }
            return messages.toArray(EMPTY_MESSAGE_ARRAY);
        }

        @Override
        public void copyMessages(Message[] msgs, Folder folder) throws MessagingException
        {
            if (!(folder instanceof LocalFolder))
            {
                throw new MessagingException("copyMessages called with incorrect Folder");
            }
            ((LocalFolder) folder).appendMessages(msgs, true);
        }

        @Override
        public void moveMessages(final Message[] msgs, final Folder destFolder) throws MessagingException
        {
            if (!(destFolder instanceof LocalFolder))
            {
                throw new MessagingException("moveMessages called with non-LocalFolder");
            }

            final LocalFolder lDestFolder = (LocalFolder)destFolder;

            try
            {
                database.execute(false, new DbCallback<Void>()
                {
                    @Override
                    public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException
                    {
                        try
                        {
                            lDestFolder.open(OpenMode.READ_WRITE);
                            for (Message message : msgs)
                            {
                                LocalMessage lMessage = (LocalMessage)message;

                                if (!message.isSet(Flag.SEEN))
                                {
                                    setUnreadMessageCount(getUnreadMessageCount() - 1);
                                    lDestFolder.setUnreadMessageCount(lDestFolder.getUnreadMessageCount() + 1);
                                }

                                if (message.isSet(Flag.FLAGGED))
                                {
                                    setFlaggedMessageCount(getFlaggedMessageCount() - 1);
                                    lDestFolder.setFlaggedMessageCount(lDestFolder.getFlaggedMessageCount() + 1);
                                }

                                String oldUID = message.getUid();

                                if (K9.DEBUG)
                                    Log.d(K9.LOG_TAG, "Updating folder_id to " + lDestFolder.getId() + " for message with UID "
                                          + message.getUid() + ", id " + lMessage.getId() + " currently in folder " + getName());

                                message.setUid(K9.LOCAL_UID_PREFIX + UUID.randomUUID().toString());

                                db.execSQL("UPDATE messages " + "SET folder_id = ?, uid = ? " + "WHERE id = ?", new Object[]
                                           {
                                               lDestFolder.getId(),
                                               message.getUid(),
                                               lMessage.getId()
                                           });

                                LocalMessage placeHolder = new LocalMessage(oldUID, LocalFolder.this);
                                placeHolder.setFlagInternal(Flag.DELETED, true);
                                placeHolder.setFlagInternal(Flag.SEEN, true);
                                appendMessages(new Message[] { placeHolder });
                            }
                        }
                        catch (MessagingException e)
                        {
                            throw new WrappedException(e);
                        }
                        return null;
                    }
                });
            }
            catch (WrappedException e)
            {
                throw (MessagingException) e.getCause();
            }

        }

        /**
         * Convenience transaction wrapper for storing a message and set it as fully downloaded. Implemented mainly to speed up DB transaction commit.
         *
         * @param message Message to store. Never <code>null</code>.
         * @param runnable What to do before setting {@link Flag#X_DOWNLOADED_FULL}. Never <code>null</code>.
         * @return The local version of the message. Never <code>null</code>.
         * @throws MessagingException
         */
        public Message storeSmallMessage(final Message message, final Runnable runnable) throws MessagingException
        {
            return database.execute(true, new DbCallback<Message>()
            {
                @Override
                public Message doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException
                {
                    try
                    {
                        appendMessages(new Message[] { message });
                        final String uid = message.getUid();
                        final Message result = getMessage(uid);
                        runnable.run();
                        // Set a flag indicating this message has now be fully downloaded
                        result.setFlag(Flag.X_DOWNLOADED_FULL, true);
                        return result;
                    }
                    catch (MessagingException e)
                    {
                        throw new WrappedException(e);
                    }
                }
            });
        }

        /**
         * The method differs slightly from the contract; If an incoming message already has a uid
         * assigned and it matches the uid of an existing message then this message will replace the
         * old message. It is implemented as a delete/insert. This functionality is used in saving
         * of drafts and re-synchronization of updated server messages.
         *
         * NOTE that although this method is located in the LocalStore class, it is not guaranteed
         * that the messages supplied as parameters are actually {@link LocalMessage} instances (in
         * fact, in most cases, they are not). Therefore, if you want to make local changes only to a
         * message, retrieve the appropriate local message instance first (if it already exists).
         */
        @Override
        public void appendMessages(Message[] messages) throws MessagingException
        {
            appendMessages(messages, false);
        }

        /**
         * The method differs slightly from the contract; If an incoming message already has a uid
         * assigned and it matches the uid of an existing message then this message will replace the
         * old message. It is implemented as a delete/insert. This functionality is used in saving
         * of drafts and re-synchronization of updated server messages.
         *
         * NOTE that although this method is located in the LocalStore class, it is not guaranteed
         * that the messages supplied as parameters are actually {@link LocalMessage} instances (in
         * fact, in most cases, they are not). Therefore, if you want to make local changes only to a
         * message, retrieve the appropriate local message instance first (if it already exists).
         * @param messages
         * @param copy
         */
        private void appendMessages(final Message[] messages, final boolean copy) throws MessagingException
        {
            open(OpenMode.READ_WRITE);
            try
            {
                database.execute(true, new DbCallback<Void>()
                {
                    @Override
                    public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException
                    {
                        try
                        {
                            for (Message message : messages)
                            {
                                if (!(message instanceof MimeMessage))
                                {
                                    throw new Error("LocalStore can only store Messages that extend MimeMessage");
                                }

                                String uid = message.getUid();
                                if (uid == null || copy)
                                {
                                    uid = K9.LOCAL_UID_PREFIX + UUID.randomUUID().toString();
                                    if (!copy)
                                    {
                                        message.setUid(uid);
                                    }
                                }
                                else
                                {
                                    Message oldMessage = getMessage(uid);
                                    if (oldMessage != null && !oldMessage.isSet(Flag.SEEN))
                                    {
                                        setUnreadMessageCount(getUnreadMessageCount() - 1);
                                    }
                                    if (oldMessage != null && oldMessage.isSet(Flag.FLAGGED))
                                    {
                                        setFlaggedMessageCount(getFlaggedMessageCount() - 1);
                                    }
                                    /*
                                     * The message may already exist in this Folder, so delete it first.
                                     */
                                    deleteAttachments(message.getUid());
                                    db.execSQL("DELETE FROM messages WHERE folder_id = ? AND uid = ?",
                                               new Object[]
                                               { mFolderId, message.getUid() });
                                }

                                ArrayList<Part> viewables = new ArrayList<Part>();
                                ArrayList<Part> attachments = new ArrayList<Part>();
                                MimeUtility.collectParts(message, viewables, attachments);

                                StringBuffer sbHtml = new StringBuffer();
                                StringBuffer sbText = new StringBuffer();
                                for (Part viewable : viewables)
                                {
                                    try
                                    {
                                        String text = MimeUtility.getTextFromPart(viewable);
                                        /*
                                         * Anything with MIME type text/html will be stored as such. Anything
                                         * else will be stored as text/plain.
                                         */
                                        if (viewable.getMimeType().equalsIgnoreCase("text/html"))
                                        {
                                            sbHtml.append(text);
                                        }
                                        else
                                        {
                                            sbText.append(text);
                                        }
                                    }
                                    catch (Exception e)
                                    {
                                        throw new MessagingException("Unable to get text for message part", e);
                                    }
                                }

                                String text = sbText.toString();
                                String html = markupContent(text, sbHtml.toString());
                                String preview = calculateContentPreview(text);
                                // If we couldn't generate a reasonable preview from the text part, try doing it with the HTML part.
                                if (preview == null || preview.length() == 0)
                                {
                                    preview = calculateContentPreview(Html.fromHtml(html).toString().replace(PREVIEW_OBJECT_CHARACTER, PREVIEW_OBJECT_REPLACEMENT));
                                }

                                try
                                {
                                    ContentValues cv = new ContentValues();
                                    cv.put("uid", uid);
                                    cv.put("subject", message.getSubject());
                                    cv.put("sender_list", Address.pack(message.getFrom()));
                                    cv.put("date", message.getSentDate() == null
                                           ? System.currentTimeMillis() : message.getSentDate().getTime());
                                    cv.put("flags", Utility.combine(message.getFlags(), ',').toUpperCase());
                                    cv.put("deleted", message.isSet(Flag.DELETED) ? 1 : 0);
                                    cv.put("folder_id", mFolderId);
                                    cv.put("to_list", Address.pack(message.getRecipients(RecipientType.TO)));
                                    cv.put("cc_list", Address.pack(message.getRecipients(RecipientType.CC)));
                                    cv.put("bcc_list", Address.pack(message.getRecipients(RecipientType.BCC)));
                                    cv.put("html_content", html.length() > 0 ? html : null);
                                    cv.put("text_content", text.length() > 0 ? text : null);
                                    cv.put("preview", preview.length() > 0 ? preview : null);
                                    cv.put("reply_to_list", Address.pack(message.getReplyTo()));
                                    cv.put("attachment_count", attachments.size());
                                    cv.put("internal_date",  message.getInternalDate() == null
                                           ? System.currentTimeMillis() : message.getInternalDate().getTime());

                                    String messageId = message.getMessageId();
                                    if (messageId != null)
                                    {
                                        cv.put("message_id", messageId);
                                    }
                                    long messageUid;
                                    messageUid = db.insert("messages", "uid", cv);
                                    for (Part attachment : attachments)
                                    {
                                        saveAttachment(messageUid, attachment, copy);
                                    }
                                    saveHeaders(messageUid, (MimeMessage)message);
                                    if (!message.isSet(Flag.SEEN))
                                    {
                                        setUnreadMessageCount(getUnreadMessageCount() + 1);
                                    }
                                    if (message.isSet(Flag.FLAGGED))
                                    {
                                        setFlaggedMessageCount(getFlaggedMessageCount() + 1);
                                    }
                                }
                                catch (Exception e)
                                {
                                    throw new MessagingException("Error appending message", e);
                                }
                            }
                        }
                        catch (MessagingException e)
                        {
                            throw new WrappedException(e);
                        }
                        return null;
                    }
                });
            }
            catch (WrappedException e)
            {
                throw (MessagingException) e.getCause();
            }
        }

        /**
         * Update the given message in the LocalStore without first deleting the existing
         * message (contrast with appendMessages). This method is used to store changes
         * to the given message while updating attachments and not removing existing
         * attachment data.
         * TODO In the future this method should be combined with appendMessages since the Message
         * contains enough data to decide what to do.
         * @param message
         * @throws MessagingException
         */
        public void updateMessage(final LocalMessage message) throws MessagingException
        {
            open(OpenMode.READ_WRITE);
            try
            {
                database.execute(false, new DbCallback<Void>()
                {
                    @Override
                    public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException
                    {
                        try
                        {
                            ArrayList<Part> viewables = new ArrayList<Part>();
                            ArrayList<Part> attachments = new ArrayList<Part>();

                            message.buildMimeRepresentation();

                            MimeUtility.collectParts(message, viewables, attachments);

                            StringBuffer sbHtml = new StringBuffer();
                            StringBuffer sbText = new StringBuffer();
                            for (int i = 0, count = viewables.size(); i < count; i++)
                            {
                                Part viewable = viewables.get(i);
                                try
                                {
                                    String text = MimeUtility.getTextFromPart(viewable);
                                    /*
                                     * Anything with MIME type text/html will be stored as such. Anything
                                     * else will be stored as text/plain.
                                     */
                                    if (viewable.getMimeType().equalsIgnoreCase("text/html"))
                                    {
                                        sbHtml.append(text);
                                    }
                                    else
                                    {
                                        sbText.append(text);
                                    }
                                }
                                catch (Exception e)
                                {
                                    throw new MessagingException("Unable to get text for message part", e);
                                }
                            }

                            String text = sbText.toString();
                            String html = markupContent(text, sbHtml.toString());
                            String preview = calculateContentPreview(text);
                            // If we couldn't generate a reasonable preview from the text part, try doing it with the HTML part.
                            if (preview == null || preview.length() == 0)
                            {
                                preview = calculateContentPreview(Html.fromHtml(html).toString().replace(PREVIEW_OBJECT_CHARACTER, PREVIEW_OBJECT_REPLACEMENT));
                            }
                            try
                            {
                                db.execSQL("UPDATE messages SET "
                                           + "uid = ?, subject = ?, sender_list = ?, date = ?, flags = ?, "
                                           + "folder_id = ?, to_list = ?, cc_list = ?, bcc_list = ?, "
                                           + "html_content = ?, text_content = ?, preview = ?, reply_to_list = ?, "
                                           + "attachment_count = ? WHERE id = ?",
                                           new Object[]
                                           {
                                               message.getUid(),
                                               message.getSubject(),
                                               Address.pack(message.getFrom()),
                                               message.getSentDate() == null ? System
                                               .currentTimeMillis() : message.getSentDate()
                                               .getTime(),
                                               Utility.combine(message.getFlags(), ',').toUpperCase(),
                                               mFolderId,
                                               Address.pack(message
                                                            .getRecipients(RecipientType.TO)),
                                               Address.pack(message
                                                            .getRecipients(RecipientType.CC)),
                                               Address.pack(message
                                                            .getRecipients(RecipientType.BCC)),
                                               html.length() > 0 ? html : null,
                                               text.length() > 0 ? text : null,
                                               preview.length() > 0 ? preview : null,
                                               Address.pack(message.getReplyTo()),
                                               attachments.size(),
                                               message.mId
                                           });

                                for (int i = 0, count = attachments.size(); i < count; i++)
                                {
                                    Part attachment = attachments.get(i);
                                    saveAttachment(message.mId, attachment, false);
                                }
                                saveHeaders(message.getId(), message);
                            }
                            catch (Exception e)
                            {
                                throw new MessagingException("Error appending message", e);
                            }
                        }
                        catch (MessagingException e)
                        {
                            throw new WrappedException(e);
                        }
                        return null;
                    }
                });
            }
            catch (WrappedException e)
            {
                throw (MessagingException) e.getCause();
            }
        }

        /**
         * Save the headers of the given message. Note that the message is not
         * necessarily a {@link LocalMessage} instance.
         * @param id
         * @param message
         * @throws com.fsck.k9.mail.MessagingException
         */
        private void saveHeaders(final long id, final MimeMessage message) throws MessagingException
        {
            database.execute(true, new DbCallback<Void>()
            {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException
                {
                    boolean saveAllHeaders = mAccount.saveAllHeaders();
                    boolean gotAdditionalHeaders = false;

                    deleteHeaders(id);
                    for (String name : message.getHeaderNames())
                    {
                        if (saveAllHeaders || HEADERS_TO_SAVE.contains(name))
                        {
                            String[] values = message.getHeader(name);
                            for (String value : values)
                            {
                                ContentValues cv = new ContentValues();
                                cv.put("message_id", id);
                                cv.put("name", name);
                                cv.put("value", value);
                                db.insert("headers", "name", cv);
                            }
                        }
                        else
                        {
                            gotAdditionalHeaders = true;
                        }
                    }

                    if (!gotAdditionalHeaders)
                    {
                        // Remember that all headers for this message have been saved, so it is
                        // not necessary to download them again in case the user wants to see all headers.
                        List<Flag> appendedFlags = new ArrayList<Flag>();
                        appendedFlags.addAll(Arrays.asList(message.getFlags()));
                        appendedFlags.add(Flag.X_GOT_ALL_HEADERS);

                        db.execSQL("UPDATE messages " + "SET flags = ? " + " WHERE id = ?",
                                   new Object[]
                                   { Utility.combine(appendedFlags.toArray(), ',').toUpperCase(), id });
                    }
                    return null;
                }
            });
        }

        private void deleteHeaders(final long id) throws UnavailableStorageException
        {
            database.execute(false, new DbCallback<Void>()
            {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException
                {
                    db.execSQL("DELETE FROM headers WHERE message_id = ?", new Object[]
                               { id });
                    return null;
                }
            });
        }

        /**
         * @param messageId
         * @param attachment
         * @param saveAsNew
         * @throws IOException
         * @throws MessagingException
         */
        private void saveAttachment(final long messageId, final Part attachment, final boolean saveAsNew)
        throws IOException, MessagingException
        {
            try
            {
                database.execute(true, new DbCallback<Void>()
                {
                    @Override
                    public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException
                    {
                        try
                        {
                            long attachmentId = -1;
                            Uri contentUri = null;
                            int size = -1;
                            File tempAttachmentFile = null;

                            if ((!saveAsNew) && (attachment instanceof LocalAttachmentBodyPart))
                            {
                                attachmentId = ((LocalAttachmentBodyPart) attachment).getAttachmentId();
                            }

                            final File attachmentDirectory = StorageManager.getInstance(mApplication).getAttachmentDirectory(uUid, database.getStorageProviderId());
                            if (attachment.getBody() != null)
                            {
                                Body body = attachment.getBody();
                                if (body instanceof LocalAttachmentBody)
                                {
                                    contentUri = ((LocalAttachmentBody) body).getContentUri();
                                }
                                else
                                {
                                    /*
                                     * If the attachment has a body we're expected to save it into the local store
                                     * so we copy the data into a cached attachment file.
                                     */
                                    InputStream in = attachment.getBody().getInputStream();
                                    tempAttachmentFile = File.createTempFile("att", null, attachmentDirectory);
                                    FileOutputStream out = new FileOutputStream(tempAttachmentFile);
                                    size = IOUtils.copy(in, out);
                                    in.close();
                                    out.close();
                                }
                            }

                            if (size == -1)
                            {
                                /*
                                 * If the attachment is not yet downloaded see if we can pull a size
                                 * off the Content-Disposition.
                                 */
                                String disposition = attachment.getDisposition();
                                if (disposition != null)
                                {
                                    String s = MimeUtility.getHeaderParameter(disposition, "size");
                                    if (s != null)
                                    {
                                        size = Integer.parseInt(s);
                                    }
                                }
                            }
                            if (size == -1)
                            {
                                size = 0;
                            }

                            String storeData =
                                Utility.combine(attachment.getHeader(
                                                    MimeHeader.HEADER_ANDROID_ATTACHMENT_STORE_DATA), ',');

                            String name = MimeUtility.getHeaderParameter(attachment.getContentType(), "name");
                            String contentId = MimeUtility.getHeaderParameter(attachment.getContentId(), null);

                            String contentDisposition = MimeUtility.unfoldAndDecode(attachment.getDisposition());
                            if (name == null && contentDisposition != null)
                            {
                                name = MimeUtility.getHeaderParameter(contentDisposition, "filename");
                            }
                            if (attachmentId == -1)
                            {
                                ContentValues cv = new ContentValues();
                                cv.put("message_id", messageId);
                                cv.put("content_uri", contentUri != null ? contentUri.toString() : null);
                                cv.put("store_data", storeData);
                                cv.put("size", size);
                                cv.put("name", name);
                                cv.put("mime_type", attachment.getMimeType());
                                cv.put("content_id", contentId);
                                cv.put("content_disposition", contentDisposition);

                                attachmentId = db.insert("attachments", "message_id", cv);
                            }
                            else
                            {
                                ContentValues cv = new ContentValues();
                                cv.put("content_uri", contentUri != null ? contentUri.toString() : null);
                                cv.put("size", size);
                                db.update("attachments", cv, "id = ?", new String[]
                                          { Long.toString(attachmentId) });
                            }

                            if (attachmentId != -1 && tempAttachmentFile != null)
                            {
                                File attachmentFile = new File(attachmentDirectory, Long.toString(attachmentId));
                                tempAttachmentFile.renameTo(attachmentFile);
                                contentUri = AttachmentProvider.getAttachmentUri(
                                                 mAccount,
                                                 attachmentId);
                                attachment.setBody(new LocalAttachmentBody(contentUri, mApplication));
                                ContentValues cv = new ContentValues();
                                cv.put("content_uri", contentUri != null ? contentUri.toString() : null);
                                db.update("attachments", cv, "id = ?", new String[]
                                          { Long.toString(attachmentId) });
                            }

                            /* The message has attachment with Content-ID */
                            if (contentId != null && contentUri != null)
                            {
                                Cursor cursor = db.query("messages", new String[]
                                                  { "html_content" }, "id = ?", new String[]
                                                  { Long.toString(messageId) }, null, null, null);
                                try
                                {
                                    if (cursor.moveToNext())
                                    {
                                        String new_html;

                                        new_html = cursor.getString(0);
                                        new_html = new_html.replaceAll("cid:" + contentId,
                                                                       contentUri.toString());

                                        ContentValues cv = new ContentValues();
                                        cv.put("html_content", new_html);
                                        db.update("messages", cv, "id = ?", new String[]
                                                  { Long.toString(messageId) });
                                    }
                                }
                                finally
                                {
                                    if (cursor != null)
                                    {
                                        cursor.close();
                                    }
                                }
                            }

                            if (attachmentId != -1 && attachment instanceof LocalAttachmentBodyPart)
                            {
                                ((LocalAttachmentBodyPart) attachment).setAttachmentId(attachmentId);
                            }
                            return null;
                        }
                        catch (MessagingException e)
                        {
                            throw new WrappedException(e);
                        }
                        catch (IOException e)
                        {
                            throw new WrappedException(e);
                        }
                    }
                });
            }
            catch (WrappedException e)
            {
                final Throwable cause = e.getCause();
                if (cause instanceof IOException)
                {
                    throw (IOException) cause;
                }
                else
                {
                    throw (MessagingException) cause;
                }
            }
        }

        /**
         * Changes the stored uid of the given message (using it's internal id as a key) to
         * the uid in the message.
         * @param message
         * @throws com.fsck.k9.mail.MessagingException
         */
        public void changeUid(final LocalMessage message) throws MessagingException
        {
            open(OpenMode.READ_WRITE);
            final ContentValues cv = new ContentValues();
            cv.put("uid", message.getUid());
            database.execute(false, new DbCallback<Void>()
            {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException
                {
                    db.update("messages", cv, "id = ?", new String[]
                              { Long.toString(message.mId) });
                    return null;
                }
            });
        }

        @Override
        public void setFlags(Message[] messages, Flag[] flags, boolean value)
        throws MessagingException
        {
            open(OpenMode.READ_WRITE);
            for (Message message : messages)
            {
                message.setFlags(flags, value);
            }
        }

        @Override
        public void setFlags(Flag[] flags, boolean value)
        throws MessagingException
        {
            open(OpenMode.READ_WRITE);
            for (Message message : getMessages(null))
            {
                message.setFlags(flags, value);
            }
        }

        @Override
        public String getUidFromMessageId(Message message) throws MessagingException
        {
            throw new MessagingException("Cannot call getUidFromMessageId on LocalFolder");
        }

        private void clearMessagesWhere(final String whereClause, final String[] params)  throws MessagingException
        {
            open(OpenMode.READ_ONLY);
            Message[] messages  = LocalStore.this.getMessages(
                                      null,
                                      this,
                                      "SELECT " + GET_MESSAGES_COLS + "FROM messages WHERE " + whereClause,
                                      params);

            for (Message message : messages)
            {
                deleteAttachments(message.getUid());
            }
            database.execute(false, new DbCallback<Void>()
            {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException
                {
                    db.execSQL("DELETE FROM messages WHERE " + whereClause, params);
                    return null;
                }
            });
            resetUnreadAndFlaggedCounts();
        }

        public void clearMessagesOlderThan(long cutoff) throws MessagingException
        {
            final String where = "folder_id = ? and date < ?";
            final String[] params = new String[]
            {
                Long.toString(mFolderId), Long.toString(cutoff)
            };

            clearMessagesWhere(where, params);
        }



        public void clearAllMessages() throws MessagingException
        {
            final String where = "folder_id = ?";
            final String[] params = new String[]
            {
                Long.toString(mFolderId)
            };


            clearMessagesWhere(where, params);
            setPushState(null);
            setLastPush(0);
            setLastChecked(0);
        }

        private void resetUnreadAndFlaggedCounts()
        {
            try
            {
                int newUnread = 0;
                int newFlagged = 0;
                Message[] messages = getMessages(null);
                for (Message message : messages)
                {
                    if (!message.isSet(Flag.SEEN))
                    {
                        newUnread++;
                    }
                    if (message.isSet(Flag.FLAGGED))
                    {
                        newFlagged++;
                    }
                }
                setUnreadMessageCount(newUnread);
                setFlaggedMessageCount(newFlagged);
            }
            catch (Exception e)
            {
                Log.e(K9.LOG_TAG, "Unable to fetch all messages from LocalStore", e);
            }
        }


        @Override
        public void delete(final boolean recurse) throws MessagingException
        {
            try
            {
                database.execute(false, new DbCallback<Void>()
                {
                    @Override
                    public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException
                    {
                        try
                        {
                            // We need to open the folder first to make sure we've got it's id
                            open(OpenMode.READ_ONLY);
                            Message[] messages = getMessages(null);
                            for (Message message : messages)
                            {
                                deleteAttachments(message.getUid());
                            }
                        }
                        catch (MessagingException e)
                        {
                            throw new WrappedException(e);
                        }
                        db.execSQL("DELETE FROM folders WHERE id = ?", new Object[]
                                   { Long.toString(mFolderId), });
                        return null;
                    }
                });
            }
            catch (WrappedException e)
            {
                throw (MessagingException) e.getCause();
            }
        }

        @Override
        public boolean equals(Object o)
        {
            if (o instanceof LocalFolder)
            {
                return ((LocalFolder)o).mName.equals(mName);
            }
            return super.equals(o);
        }

        @Override
        public int hashCode()
        {
            return mName.hashCode();
        }

        @Override
        public Flag[] getPermanentFlags()
        {
            return PERMANENT_FLAGS;
        }


        private void deleteAttachments(final long messageId) throws MessagingException
        {
            open(OpenMode.READ_WRITE);
            database.execute(false, new DbCallback<Void>()
            {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException
                {
                    Cursor attachmentsCursor = null;
                    try
                    {
                        attachmentsCursor = db.query("attachments", new String[]
                                                     { "id" }, "message_id = ?", new String[]
                                                     { Long.toString(messageId) }, null, null, null);
                        final File attachmentDirectory = StorageManager.getInstance(mApplication)
                                                         .getAttachmentDirectory(uUid, database.getStorageProviderId());
                        while (attachmentsCursor.moveToNext())
                        {
                            long attachmentId = attachmentsCursor.getLong(0);
                            try
                            {
                                File file = new File(attachmentDirectory, Long.toString(attachmentId));
                                if (file.exists())
                                {
                                    file.delete();
                                }
                            }
                            catch (Exception e)
                            {

                            }
                        }
                    }
                    finally
                    {
                        if (attachmentsCursor != null)
                        {
                            attachmentsCursor.close();
                        }
                    }
                    return null;
                }
            });
        }

        private void deleteAttachments(final String uid) throws MessagingException
        {
            open(OpenMode.READ_WRITE);
            try
            {
                database.execute(false, new DbCallback<Void>()
                {
                    @Override
                    public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException
                    {
                        Cursor messagesCursor = null;
                        try
                        {
                            messagesCursor = db.query("messages", new String[]
                                                      { "id" }, "folder_id = ? AND uid = ?", new String[]
                                                      { Long.toString(mFolderId), uid }, null, null, null);
                            while (messagesCursor.moveToNext())
                            {
                                long messageId = messagesCursor.getLong(0);
                                deleteAttachments(messageId);

                            }
                        }
                        catch (MessagingException e)
                        {
                            throw new WrappedException(e);
                        }
                        finally
                        {
                            if (messagesCursor != null)
                            {
                                messagesCursor.close();
                            }
                        }
                        return null;
                    }
                });
            }
            catch (WrappedException e)
            {
                throw (MessagingException) e.getCause();
            }
        }

        /*
         * calculateContentPreview
         * Takes a plain text message body as a string.
         * Returns a message summary as a string suitable for showing in a message list
         *
         * A message summary should be about the first 160 characters
         * of unique text written by the message sender
         * Quoted text, "On $date" and so on will be stripped out.
         * All newlines and whitespace will be compressed.
         *
         */
        public String calculateContentPreview(String text)
        {
            if (text == null)
            {
                return null;
            }

            // Only look at the first 8k of a message when calculating
            // the preview.  This should avoid unnecessary
            // memory usage on large messages
            if (text.length() > 8192)
            {
                text = text.substring(0,8192);
            }


            text = text.replaceAll("(?m)^----.*?$","");
            text = text.replaceAll("(?m)^[#>].*$","");
            text = text.replaceAll("(?m)^On .*wrote.?$","");
            text = text.replaceAll("(?m)^.*\\w+:$","");
            text = text.replaceAll("https?://\\S+","...");
            text = text.replaceAll("(\\r|\\n)+"," ");
            text = text.replaceAll("\\s+"," ");
            if (text.length() <= 512)
            {
                return text;
            }
            else
            {
                text = text.substring(0,512);
                return text;
            }

        }

        public String markupContent(String text, String html)
        {
            if (text.length() > 0 && html.length() == 0)
            {
                html = htmlifyString(text);
            }

            html = convertEmoji2Img(html);

            return html;
        }

        public String htmlifyString(String text)
        {
            // Our HTMLification code is somewhat memory intensive
            // and was causing lots of OOM errors on the market
            // if the message is big and plain text, just do
            // a trivial htmlification
            if (text.length() > MAX_SMART_HTMLIFY_MESSAGE_LENGTH)
            {
                return "<html><head/><body>" +
                       htmlifyMessageHeader() +
                       text +
                       htmlifyMessageFooter() +
                       "</body></html>";
            }
            StringReader reader = new StringReader(text);
            StringBuilder buff = new StringBuilder(text.length() + 512);
            int c;
            try
            {
                while ((c = reader.read()) != -1)
                {
                    switch (c)
                    {
                        case '&':
                            buff.append("&amp;");
                            break;
                        case '<':
                            buff.append("&lt;");
                            break;
                        case '>':
                            buff.append("&gt;");
                            break;
                        case '\r':
                            break;
                        default:
                            buff.append((char)c);
                    }//switch
                }
            }
            catch (IOException e)
            {
                //Should never happen
                Log.e(K9.LOG_TAG, null, e);
            }
            text = buff.toString();
            text = text.replaceAll("\\s*([-=_]{30,}+)\\s*","<hr />");
            text = text.replaceAll("(?m)^([^\r\n]{4,}[\\s\\w,:;+/])(?:\r\n|\n|\r)(?=[a-z]\\S{0,10}[\\s\\n\\r])","$1 ");
            text = text.replaceAll("(?m)(\r\n|\n|\r){4,}","\n\n");


            Matcher m = Regex.WEB_URL_PATTERN.matcher(text);
            StringBuffer sb = new StringBuffer(text.length() + 512);
            sb.append("<html><head></head><body>");
            sb.append(htmlifyMessageHeader());
            while (m.find())
            {
                int start = m.start();
                if (start == 0 || (start != 0 && text.charAt(start - 1) != '@'))
                {
                    if (m.group().indexOf(':') > 0)   // With no URI-schema we may get "http:/" links with the second / missing
                    {
                        m.appendReplacement(sb, "<a href=\"$0\">$0</a>");
                    }
                    else
                    {
                        m.appendReplacement(sb, "<a href=\"http://$0\">$0</a>");
                    }
                }
                else
                {
                    m.appendReplacement(sb, "$0");
                }
            }




            m.appendTail(sb);
            sb.append(htmlifyMessageFooter());
            sb.append("</body></html>");
            text = sb.toString();

            return text;
        }

        private String htmlifyMessageHeader()
        {
            if (K9.messageViewFixedWidthFont())
            {
                return "<pre style=\"white-space: pre-wrap; word-wrap:break-word; \">";
            }
            else
            {
                return "<div style=\"white-space: pre-wrap; word-wrap:break-word; \">";
            }
        }


        private String htmlifyMessageFooter()
        {
            if (K9.messageViewFixedWidthFont())
            {
                return "</pre>";
            }
            else
            {
                return "</div>";
            }
        }

        public String convertEmoji2Img(String html)
        {
            StringBuilder buff = new StringBuilder(html.length() + 512);
            for (int i = 0; i < html.length(); i = html.offsetByCodePoints(i, 1))
             {
                int codePoint = html.codePointAt(i);
                String emoji = getEmojiForCodePoint(codePoint);
                if (emoji != null)
                    buff.append("<img src=\"file:///android_asset/emoticons/" + emoji + ".gif\" alt=\"" + emoji + "\" />");
                else
                    buff.appendCodePoint(codePoint);

            }
            return buff.toString();
        }
        private String getEmojiForCodePoint(int codePoint)
        {
            // Derived from http://code.google.com/p/emoji4unicode/source/browse/trunk/data/emoji4unicode.xml
            // XXX: This doesn't cover all the characters.  More emoticons are wanted.
            switch (codePoint)
            {
            case 0xFE000: return "sun";
            case 0xFE001: return "cloud";
            case 0xFE002: return "rain";
            case 0xFE003: return "snow";
            case 0xFE004: return "thunder";
            case 0xFE005: return "typhoon";
            case 0xFE006: return "mist";
            case 0xFE007: return "sprinkle";
            case 0xFE008: return "night";
            case 0xFE009: return "sun";
            case 0xFE00A: return "sun";
            case 0xFE00C: return "sun";
            case 0xFE010: return "night";
            case 0xFE011: return "newmoon";
            case 0xFE012: return "moon1";
            case 0xFE013: return "moon2";
            case 0xFE014: return "moon3";
            case 0xFE015: return "fullmoon";
            case 0xFE016: return "moon2";
            case 0xFE018: return "soon";
            case 0xFE019: return "on";
            case 0xFE01A: return "end";
            case 0xFE01B: return "sandclock";
            case 0xFE01C: return "sandclock";
            case 0xFE01D: return "watch";
            case 0xFE01E: return "clock";
            case 0xFE01F: return "clock";
            case 0xFE020: return "clock";
            case 0xFE021: return "clock";
            case 0xFE022: return "clock";
            case 0xFE023: return "clock";
            case 0xFE024: return "clock";
            case 0xFE025: return "clock";
            case 0xFE026: return "clock";
            case 0xFE027: return "clock";
            case 0xFE028: return "clock";
            case 0xFE029: return "clock";
            case 0xFE02A: return "clock";
            case 0xFE02B: return "aries";
            case 0xFE02C: return "taurus";
            case 0xFE02D: return "gemini";
            case 0xFE02E: return "cancer";
            case 0xFE02F: return "leo";
            case 0xFE030: return "virgo";
            case 0xFE031: return "libra";
            case 0xFE032: return "scorpius";
            case 0xFE033: return "sagittarius";
            case 0xFE034: return "capricornus";
            case 0xFE035: return "aquarius";
            case 0xFE036: return "pisces";
            case 0xFE038: return "wave";
            case 0xFE03B: return "night";
            case 0xFE03C: return "clover";
            case 0xFE03D: return "tulip";
            case 0xFE03E: return "bud";
            case 0xFE03F: return "maple";
            case 0xFE040: return "cherryblossom";
            case 0xFE042: return "maple";
            case 0xFE04E: return "clover";
            case 0xFE04F: return "cherry";
            case 0xFE050: return "banana";
            case 0xFE051: return "apple";
            case 0xFE05B: return "apple";
            case 0xFE190: return "eye";
            case 0xFE191: return "ear";
            case 0xFE193: return "kissmark";
            case 0xFE194: return "bleah";
            case 0xFE195: return "rouge";
            case 0xFE198: return "hairsalon";
            case 0xFE19A: return "shadow";
            case 0xFE19B: return "happy01";
            case 0xFE19C: return "happy01";
            case 0xFE19D: return "happy01";
            case 0xFE19E: return "happy01";
            case 0xFE1B7: return "dog";
            case 0xFE1B8: return "cat";
            case 0xFE1B9: return "snail";
            case 0xFE1BA: return "chick";
            case 0xFE1BB: return "chick";
            case 0xFE1BC: return "penguin";
            case 0xFE1BD: return "fish";
            case 0xFE1BE: return "horse";
            case 0xFE1BF: return "pig";
            case 0xFE1C8: return "chick";
            case 0xFE1C9: return "fish";
            case 0xFE1CF: return "aries";
            case 0xFE1D0: return "dog";
            case 0xFE1D8: return "dog";
            case 0xFE1D9: return "fish";
            case 0xFE1DB: return "foot";
            case 0xFE1DD: return "chick";
            case 0xFE1E0: return "pig";
            case 0xFE1E3: return "cancer";
            case 0xFE320: return "angry";
            case 0xFE321: return "sad";
            case 0xFE322: return "wobbly";
            case 0xFE323: return "despair";
            case 0xFE324: return "wobbly";
            case 0xFE325: return "coldsweats02";
            case 0xFE326: return "gawk";
            case 0xFE327: return "lovely";
            case 0xFE328: return "smile";
            case 0xFE329: return "bleah";
            case 0xFE32A: return "bleah";
            case 0xFE32B: return "delicious";
            case 0xFE32C: return "lovely";
            case 0xFE32D: return "lovely";
            case 0xFE32F: return "happy02";
            case 0xFE330: return "happy01";
            case 0xFE331: return "coldsweats01";
            case 0xFE332: return "happy02";
            case 0xFE333: return "smile";
            case 0xFE334: return "happy02";
            case 0xFE335: return "delicious";
            case 0xFE336: return "happy01";
            case 0xFE337: return "happy01";
            case 0xFE338: return "coldsweats01";
            case 0xFE339: return "weep";
            case 0xFE33A: return "crying";
            case 0xFE33B: return "shock";
            case 0xFE33C: return "bearing";
            case 0xFE33D: return "pout";
            case 0xFE33E: return "confident";
            case 0xFE33F: return "sad";
            case 0xFE340: return "think";
            case 0xFE341: return "shock";
            case 0xFE342: return "sleepy";
            case 0xFE343: return "catface";
            case 0xFE344: return "coldsweats02";
            case 0xFE345: return "coldsweats02";
            case 0xFE346: return "bearing";
            case 0xFE347: return "wink";
            case 0xFE348: return "happy01";
            case 0xFE349: return "smile";
            case 0xFE34A: return "happy02";
            case 0xFE34B: return "lovely";
            case 0xFE34C: return "lovely";
            case 0xFE34D: return "weep";
            case 0xFE34E: return "pout";
            case 0xFE34F: return "smile";
            case 0xFE350: return "sad";
            case 0xFE351: return "ng";
            case 0xFE352: return "ok";
            case 0xFE357: return "paper";
            case 0xFE359: return "sad";
            case 0xFE35A: return "angry";
            case 0xFE4B0: return "house";
            case 0xFE4B1: return "house";
            case 0xFE4B2: return "building";
            case 0xFE4B3: return "postoffice";
            case 0xFE4B4: return "hospital";
            case 0xFE4B5: return "bank";
            case 0xFE4B6: return "atm";
            case 0xFE4B7: return "hotel";
            case 0xFE4B9: return "24hours";
            case 0xFE4BA: return "school";
            case 0xFE4C1: return "ship";
            case 0xFE4C2: return "bottle";
            case 0xFE4C3: return "fuji";
            case 0xFE4C9: return "wrench";
            case 0xFE4CC: return "shoe";
            case 0xFE4CD: return "shoe";
            case 0xFE4CE: return "eyeglass";
            case 0xFE4CF: return "t-shirt";
            case 0xFE4D0: return "denim";
            case 0xFE4D1: return "crown";
            case 0xFE4D2: return "crown";
            case 0xFE4D6: return "boutique";
            case 0xFE4D7: return "boutique";
            case 0xFE4DB: return "t-shirt";
            case 0xFE4DC: return "moneybag";
            case 0xFE4DD: return "dollar";
            case 0xFE4E0: return "dollar";
            case 0xFE4E2: return "yen";
            case 0xFE4E3: return "dollar";
            case 0xFE4EF: return "camera";
            case 0xFE4F0: return "bag";
            case 0xFE4F1: return "pouch";
            case 0xFE4F2: return "bell";
            case 0xFE4F3: return "door";
            case 0xFE4F9: return "movie";
            case 0xFE4FB: return "flair";
            case 0xFE4FD: return "sign05";
            case 0xFE4FF: return "book";
            case 0xFE500: return "book";
            case 0xFE501: return "book";
            case 0xFE502: return "book";
            case 0xFE503: return "book";
            case 0xFE505: return "spa";
            case 0xFE506: return "toilet";
            case 0xFE507: return "toilet";
            case 0xFE508: return "toilet";
            case 0xFE50F: return "ribbon";
            case 0xFE510: return "present";
            case 0xFE511: return "birthday";
            case 0xFE512: return "xmas";
            case 0xFE522: return "pocketbell";
            case 0xFE523: return "telephone";
            case 0xFE524: return "telephone";
            case 0xFE525: return "mobilephone";
            case 0xFE526: return "phoneto";
            case 0xFE527: return "memo";
            case 0xFE528: return "faxto";
            case 0xFE529: return "mail";
            case 0xFE52A: return "mailto";
            case 0xFE52B: return "mailto";
            case 0xFE52C: return "postoffice";
            case 0xFE52D: return "postoffice";
            case 0xFE52E: return "postoffice";
            case 0xFE535: return "present";
            case 0xFE536: return "pen";
            case 0xFE537: return "chair";
            case 0xFE538: return "pc";
            case 0xFE539: return "pencil";
            case 0xFE53A: return "clip";
            case 0xFE53B: return "bag";
            case 0xFE53E: return "hairsalon";
            case 0xFE540: return "memo";
            case 0xFE541: return "memo";
            case 0xFE545: return "book";
            case 0xFE546: return "book";
            case 0xFE547: return "book";
            case 0xFE548: return "memo";
            case 0xFE54D: return "book";
            case 0xFE54F: return "book";
            case 0xFE552: return "memo";
            case 0xFE553: return "foot";
            case 0xFE7D0: return "sports";
            case 0xFE7D1: return "baseball";
            case 0xFE7D2: return "golf";
            case 0xFE7D3: return "tennis";
            case 0xFE7D4: return "soccer";
            case 0xFE7D5: return "ski";
            case 0xFE7D6: return "basketball";
            case 0xFE7D7: return "motorsports";
            case 0xFE7D8: return "snowboard";
            case 0xFE7D9: return "run";
            case 0xFE7DA: return "snowboard";
            case 0xFE7DC: return "horse";
            case 0xFE7DF: return "train";
            case 0xFE7E0: return "subway";
            case 0xFE7E1: return "subway";
            case 0xFE7E2: return "bullettrain";
            case 0xFE7E3: return "bullettrain";
            case 0xFE7E4: return "car";
            case 0xFE7E5: return "rvcar";
            case 0xFE7E6: return "bus";
            case 0xFE7E8: return "ship";
            case 0xFE7E9: return "airplane";
            case 0xFE7EA: return "yacht";
            case 0xFE7EB: return "bicycle";
            case 0xFE7EE: return "yacht";
            case 0xFE7EF: return "car";
            case 0xFE7F0: return "run";
            case 0xFE7F5: return "gasstation";
            case 0xFE7F6: return "parking";
            case 0xFE7F7: return "signaler";
            case 0xFE7FA: return "spa";
            case 0xFE7FC: return "carouselpony";
            case 0xFE7FF: return "fish";
            case 0xFE800: return "karaoke";
            case 0xFE801: return "movie";
            case 0xFE802: return "movie";
            case 0xFE803: return "music";
            case 0xFE804: return "art";
            case 0xFE805: return "drama";
            case 0xFE806: return "event";
            case 0xFE807: return "ticket";
            case 0xFE808: return "slate";
            case 0xFE809: return "drama";
            case 0xFE80A: return "game";
            case 0xFE813: return "note";
            case 0xFE814: return "notes";
            case 0xFE81A: return "notes";
            case 0xFE81C: return "tv";
            case 0xFE81D: return "cd";
            case 0xFE81E: return "cd";
            case 0xFE823: return "kissmark";
            case 0xFE824: return "loveletter";
            case 0xFE825: return "ring";
            case 0xFE826: return "ring";
            case 0xFE827: return "kissmark";
            case 0xFE829: return "heart02";
            case 0xFE82B: return "freedial";
            case 0xFE82C: return "sharp";
            case 0xFE82D: return "mobaq";
            case 0xFE82E: return "one";
            case 0xFE82F: return "two";
            case 0xFE830: return "three";
            case 0xFE831: return "four";
            case 0xFE832: return "five";
            case 0xFE833: return "six";
            case 0xFE834: return "seven";
            case 0xFE835: return "eight";
            case 0xFE836: return "nine";
            case 0xFE837: return "zero";
            case 0xFE960: return "fastfood";
            case 0xFE961: return "riceball";
            case 0xFE962: return "cake";
            case 0xFE963: return "noodle";
            case 0xFE964: return "bread";
            case 0xFE96A: return "noodle";
            case 0xFE973: return "typhoon";
            case 0xFE980: return "restaurant";
            case 0xFE981: return "cafe";
            case 0xFE982: return "bar";
            case 0xFE983: return "beer";
            case 0xFE984: return "japanesetea";
            case 0xFE985: return "bottle";
            case 0xFE986: return "wine";
            case 0xFE987: return "beer";
            case 0xFE988: return "bar";
            case 0xFEAF0: return "upwardright";
            case 0xFEAF1: return "downwardright";
            case 0xFEAF2: return "upwardleft";
            case 0xFEAF3: return "downwardleft";
            case 0xFEAF4: return "up";
            case 0xFEAF5: return "down";
            case 0xFEAF6: return "leftright";
            case 0xFEAF7: return "updown";
            case 0xFEB04: return "sign01";
            case 0xFEB05: return "sign02";
            case 0xFEB06: return "sign03";
            case 0xFEB07: return "sign04";
            case 0xFEB08: return "sign05";
            case 0xFEB0B: return "sign01";
            case 0xFEB0C: return "heart01";
            case 0xFEB0D: return "heart02";
            case 0xFEB0E: return "heart03";
            case 0xFEB0F: return "heart04";
            case 0xFEB10: return "heart01";
            case 0xFEB11: return "heart02";
            case 0xFEB12: return "heart01";
            case 0xFEB13: return "heart01";
            case 0xFEB14: return "heart01";
            case 0xFEB15: return "heart01";
            case 0xFEB16: return "heart01";
            case 0xFEB17: return "heart01";
            case 0xFEB18: return "heart02";
            case 0xFEB19: return "cute";
            case 0xFEB1A: return "heart";
            case 0xFEB1B: return "spade";
            case 0xFEB1C: return "diamond";
            case 0xFEB1D: return "club";
            case 0xFEB1E: return "smoking";
            case 0xFEB1F: return "nosmoking";
            case 0xFEB20: return "wheelchair";
            case 0xFEB21: return "free";
            case 0xFEB22: return "flag";
            case 0xFEB23: return "danger";
            case 0xFEB26: return "ng";
            case 0xFEB27: return "ok";
            case 0xFEB28: return "ng";
            case 0xFEB29: return "copyright";
            case 0xFEB2A: return "tm";
            case 0xFEB2B: return "secret";
            case 0xFEB2C: return "recycle";
            case 0xFEB2D: return "r-mark";
            case 0xFEB2E: return "ban";
            case 0xFEB2F: return "empty";
            case 0xFEB30: return "pass";
            case 0xFEB31: return "full";
            case 0xFEB36: return "new";
            case 0xFEB44: return "fullmoon";
            case 0xFEB48: return "ban";
            case 0xFEB55: return "cute";
            case 0xFEB56: return "flair";
            case 0xFEB57: return "annoy";
            case 0xFEB58: return "bomb";
            case 0xFEB59: return "sleepy";
            case 0xFEB5A: return "impact";
            case 0xFEB5B: return "sweat01";
            case 0xFEB5C: return "sweat02";
            case 0xFEB5D: return "dash";
            case 0xFEB5F: return "sad";
            case 0xFEB60: return "shine";
            case 0xFEB61: return "cute";
            case 0xFEB62: return "cute";
            case 0xFEB63: return "newmoon";
            case 0xFEB64: return "newmoon";
            case 0xFEB65: return "newmoon";
            case 0xFEB66: return "newmoon";
            case 0xFEB67: return "newmoon";
            case 0xFEB77: return "shine";
            case 0xFEB81: return "id";
            case 0xFEB82: return "key";
            case 0xFEB83: return "enter";
            case 0xFEB84: return "clear";
            case 0xFEB85: return "search";
            case 0xFEB86: return "key";
            case 0xFEB87: return "key";
            case 0xFEB8A: return "key";
            case 0xFEB8D: return "search";
            case 0xFEB90: return "key";
            case 0xFEB91: return "recycle";
            case 0xFEB92: return "mail";
            case 0xFEB93: return "rock";
            case 0xFEB94: return "scissors";
            case 0xFEB95: return "paper";
            case 0xFEB96: return "punch";
            case 0xFEB97: return "good";
            case 0xFEB9D: return "paper";
            case 0xFEB9F: return "ok";
            case 0xFEBA0: return "down";
            case 0xFEBA1: return "paper";
            case 0xFEE10: return "info01";
            case 0xFEE11: return "info02";
            case 0xFEE12: return "by-d";
            case 0xFEE13: return "d-point";
            case 0xFEE14: return "appli01";
            case 0xFEE15: return "appli02";
            case 0xFEE1C: return "movie";
            default: return null;
            }
        }

        @Override
        public boolean isInTopGroup()
        {
            return inTopGroup;
        }

        public void setInTopGroup(boolean inTopGroup)
        {
            this.inTopGroup = inTopGroup;
        }

        public Integer getLastUid()
        {
            return mLastUid;
        }

        /**
         * <p>Fetches the most recent <b>numeric</b> UID value in this folder.  This is used by
         * {@link com.fsck.k9.controller.MessagingController#shouldNotifyForMessage} to see if messages being
         * fetched are new and unread.  Messages are "new" if they have a UID higher than the most recent UID prior
         * to synchronization.</p>
         *
         * <p>This only works for protocols with numeric UIDs (like IMAP). For protocols with
         * alphanumeric UIDs (like POP), this method quietly fails and shouldNotifyForMessage() will
         * always notify for unread messages.</p>
         *
         * <p>Once Issue 1072 has been fixed, this method and shouldNotifyForMessage() should be
         * updated to use internal dates rather than UIDs to determine new-ness. While this doesn't
         * solve things for POP (which doesn't have internal dates), we can likely use this as a
         * framework to examine send date in lieu of internal date.</p>
         * @throws MessagingException
         */
        public void updateLastUid() throws MessagingException
        {
            Integer lastUid = database.execute(false, new DbCallback<Integer>()
            {
                @Override
                public Integer doDbWork(final SQLiteDatabase db)
                {
                    Cursor cursor = null;
                    try
                    {
                        open(OpenMode.READ_ONLY);
                        cursor = db.rawQuery("SELECT MAX(uid) FROM messages WHERE folder_id=?", new String[] { Long.toString(mFolderId) });
                        if (cursor.getCount() > 0)
                        {
                            cursor.moveToFirst();
                            return cursor.getInt(0);
                        }
                    }
                    catch (Exception e)
                    {
                        Log.e(K9.LOG_TAG, "Unable to updateLastUid: ", e);
                    }
                    finally
                    {
                        if (cursor != null)
                        {
                            cursor.close();
                        }
                    }
                    return null;
                }
            });
            if(K9.DEBUG)
                Log.d(K9.LOG_TAG, "Updated last UID for folder " + mName + " to " + lastUid);
            mLastUid = lastUid;
        }
    }

    public static class LocalTextBody extends TextBody
    {
        /**
         * This is an HTML-ified version of the message for display purposes.
         */
        private String mBodyForDisplay;

        public LocalTextBody(String body)
        {
            super(body);
        }

        public LocalTextBody(String body, String bodyForDisplay)
        {
            super(body);
            this.mBodyForDisplay = bodyForDisplay;
        }

        public String getBodyForDisplay()
        {
            return mBodyForDisplay;
        }

        public void setBodyForDisplay(String mBodyForDisplay)
        {
            this.mBodyForDisplay = mBodyForDisplay;
        }

    }//LocalTextBody

    public class LocalMessage extends MimeMessage
    {
        private long mId;
        private int mAttachmentCount;
        private String mSubject;

        private String mPreview = "";

        private boolean mToMeCalculated = false;
        private boolean mCcMeCalculated = false;
        private boolean mToMe = false;
        private boolean mCcMe = false;


        private boolean mHeadersLoaded = false;
        private boolean mMessageDirty = false;

        public LocalMessage()
        {
        }

        LocalMessage(String uid, Folder folder)
        {
            this.mUid = uid;
            this.mFolder = folder;
        }

        private void populateFromGetMessageCursor(Cursor cursor)
        throws MessagingException
        {
            final String subject = cursor.getString(0);
            this.setSubject(subject == null ? "" : subject);

            Address[] from = Address.unpack(cursor.getString(1));
            if (from.length > 0)
            {
                this.setFrom(from[0]);
            }
            this.setInternalSentDate(new Date(cursor.getLong(2)));
            this.setUid(cursor.getString(3));
            String flagList = cursor.getString(4);
            if (flagList != null && flagList.length() > 0)
            {
                String[] flags = flagList.split(",");

                for (String flag : flags)
                {
                    try
                    {
                        this.setFlagInternal(Flag.valueOf(flag), true);
                    }

                    catch (Exception e)
                    {
                        if (!"X_BAD_FLAG".equals(flag))
                        {
                            Log.w(K9.LOG_TAG, "Unable to parse flag " + flag);
                        }
                    }
                }
            }
            this.mId = cursor.getLong(5);
            this.setRecipients(RecipientType.TO, Address.unpack(cursor.getString(6)));
            this.setRecipients(RecipientType.CC, Address.unpack(cursor.getString(7)));
            this.setRecipients(RecipientType.BCC, Address.unpack(cursor.getString(8)));
            this.setReplyTo(Address.unpack(cursor.getString(9)));

            this.mAttachmentCount = cursor.getInt(10);
            this.setInternalDate(new Date(cursor.getLong(11)));
            this.setMessageId(cursor.getString(12));

            final String preview = cursor.getString(14);
            mPreview = (preview == null ? "" : preview);

            if (this.mFolder == null)
            {
                LocalFolder f = new LocalFolder(cursor.getInt(13));
                f.open(LocalFolder.OpenMode.READ_WRITE);
                this.mFolder = f;
            }
        }

        /**
         * Fetch the message text for display. This always returns an HTML-ified version of the
         * message, even if it was originally a text-only message.
         * @return HTML version of message for display purposes.
         * @throws MessagingException
         */
        public String getTextForDisplay() throws MessagingException
        {
            String text;    // First try and fetch an HTML part.
            Part part = MimeUtility.findFirstPartByMimeType(this, "text/html");
            if (part == null)
            {
                // If that fails, try and get a text part.
                part = MimeUtility.findFirstPartByMimeType(this, "text/plain");
                if (part == null)
                {
                    text = null;
                }
                else
                {
                    LocalStore.LocalTextBody body = (LocalStore.LocalTextBody) part.getBody();
                    if (body == null)
                    {
                        text = null;
                    }
                    else
                    {
                        text = body.getBodyForDisplay();
                    }
                }
            }
            else
            {
                // We successfully found an HTML part; do the necessary character set decoding.
                text = MimeUtility.getTextFromPart(part);
            }
            return text;
        }


        /* Custom version of writeTo that updates the MIME message based on localMessage
         * changes.
         */

        @Override
        public void writeTo(OutputStream out) throws IOException, MessagingException
        {
            if (mMessageDirty) buildMimeRepresentation();
            super.writeTo(out);
        }

        private void buildMimeRepresentation() throws MessagingException
        {
            if (!mMessageDirty)
            {
                return;
            }

            super.setSubject(mSubject);
            if (this.mFrom != null && this.mFrom.length > 0)
            {
                super.setFrom(this.mFrom[0]);
            }

            super.setReplyTo(mReplyTo);
            super.setSentDate(this.getSentDate());
            super.setRecipients(RecipientType.TO, mTo);
            super.setRecipients(RecipientType.CC, mCc);
            super.setRecipients(RecipientType.BCC, mBcc);
            if (mMessageId != null) super.setMessageId(mMessageId);

            mMessageDirty = false;
        }

        public String getPreview()
        {
            return mPreview;
        }

        @Override
        public String getSubject()
        {
            return mSubject;
        }


        @Override
        public void setSubject(String subject) throws MessagingException
        {
            mSubject = subject;
            mMessageDirty = true;
        }


        @Override
        public void setMessageId(String messageId)
        {
            mMessageId = messageId;
            mMessageDirty = true;
        }

        public boolean hasAttachments()
        {
            if (mAttachmentCount > 0)
            {
                return true;
            }
            else
            {
                return false;
            }

        }

        public int getAttachmentCount()
        {
            return mAttachmentCount;
        }

        @Override
        public void setFrom(Address from) throws MessagingException
        {
            this.mFrom = new Address[] { from };
            mMessageDirty = true;
        }


        @Override
        public void setReplyTo(Address[] replyTo) throws MessagingException
        {
            if (replyTo == null || replyTo.length == 0)
            {
                mReplyTo = null;
            }
            else
            {
                mReplyTo = replyTo;
            }
            mMessageDirty = true;
        }


        /*
         * For performance reasons, we add headers instead of setting them (see super implementation)
         * which removes (expensive) them before adding them
         */
        @Override
        public void setRecipients(RecipientType type, Address[] addresses) throws MessagingException
        {
            if (type == RecipientType.TO)
            {
                if (addresses == null || addresses.length == 0)
                {
                    this.mTo = null;
                }
                else
                {
                    this.mTo = addresses;
                }
            }
            else if (type == RecipientType.CC)
            {
                if (addresses == null || addresses.length == 0)
                {
                    this.mCc = null;
                }
                else
                {
                    this.mCc = addresses;
                }
            }
            else if (type == RecipientType.BCC)
            {
                if (addresses == null || addresses.length == 0)
                {
                    this.mBcc = null;
                }
                else
                {
                    this.mBcc = addresses;
                }
            }
            else
            {
                throw new MessagingException("Unrecognized recipient type.");
            }
            mMessageDirty = true;
        }



        public boolean toMe()
        {
            try
            {
                if (!mToMeCalculated)
                {
                    for (Address address : getRecipients(RecipientType.TO))
                    {
                        if (mAccount.isAnIdentity(address))
                        {
                            mToMe = true;
                            mToMeCalculated = true;
                        }
                    }
                }
            }
            catch (MessagingException e)
            {
                // do something better than ignore this
                // getRecipients can throw a messagingexception
            }
            return mToMe;
        }





        public boolean ccMe()
        {
            try
            {

                if (!mCcMeCalculated)
                {
                    for(Address address : getRecipients(RecipientType.CC))
                    {
                        if (mAccount.isAnIdentity(address))
                        {
                            mCcMe = true;
                            mCcMeCalculated = true;
                        }
                    }

                }
            }
            catch (MessagingException e)
            {
                // do something better than ignore this
                // getRecipients can throw a messagingexception
            }

            return mCcMe;
        }






        public void setFlagInternal(Flag flag, boolean set) throws MessagingException
        {
            super.setFlag(flag, set);
        }

        public long getId()
        {
            return mId;
        }

        @Override
        public void setFlag(final Flag flag, final boolean set) throws MessagingException
        {

            try
            {
                database.execute(true, new DbCallback<Void>()
                {
                    @Override
                    public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException
                    {
                        try
                        {
                            if (flag == Flag.DELETED && set)
                            {
                                delete();
                            }

                            updateFolderCountsOnFlag(flag, set);


                            LocalMessage.super.setFlag(flag, set);
                        }
                        catch (MessagingException e)
                        {
                            throw new WrappedException(e);
                        }
                        /*
                         * Set the flags on the message.
                         */
                        db.execSQL("UPDATE messages " + "SET flags = ? " + " WHERE id = ?", new Object[]
                                   { Utility.combine(getFlags(), ',').toUpperCase(), mId });
                        return null;
                    }
                });
            }
            catch (WrappedException e)
            {
                throw (MessagingException) e.getCause();
            }


        }

        /*
         * If a message is being marked as deleted we want to clear out it's content
         * and attachments as well. Delete will not actually remove the row since we need
         * to retain the uid for synchronization purposes.
         */
        private void delete() throws MessagingException

        {
            /*
             * Delete all of the message's content to save space.
             */
            try
            {
                database.execute(true, new DbCallback<Void>()
                {
                    @Override
                    public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException
                    {
                        db.execSQL("UPDATE messages SET " + "deleted = 1," + "subject = NULL, "
                                   + "sender_list = NULL, " + "date = NULL, " + "to_list = NULL, "
                                   + "cc_list = NULL, " + "bcc_list = NULL, " + "preview = NULL, "
                                   + "html_content = NULL, " + "text_content = NULL, "
                                   + "reply_to_list = NULL " + "WHERE id = ?", new Object[]
                                   { mId });
                        /*
                         * Delete all of the message's attachments to save space.
                         * We do this explicit deletion here because we're not deleting the record
                         * in messages, which means our ON DELETE trigger for messages won't cascade
                         */
                        try
                        {
                            ((LocalFolder) mFolder).deleteAttachments(mId);
                        }
                        catch (MessagingException e)
                        {
                            throw new WrappedException(e);
                        }
                        db.execSQL("DELETE FROM attachments WHERE message_id = ?", new Object[]
                                   { mId });
                        return null;
                    }
                });
            }
            catch (WrappedException e)
            {
                throw (MessagingException) e.getCause();
            }
            ((LocalFolder)mFolder).deleteHeaders(mId);


        }

        /*
         * Completely remove a message from the local database
         */
        @Override
        public void destroy() throws MessagingException
        {
            try
            {
                database.execute(true, new DbCallback<Void>()
                {
                    @Override
                    public Void doDbWork(final SQLiteDatabase db) throws WrappedException,
                        UnavailableStorageException
                    {
                        try
                        {
                            updateFolderCountsOnFlag(Flag.X_DESTROYED, true);
                            ((LocalFolder) mFolder).deleteAttachments(mId);
                            db.execSQL("DELETE FROM messages WHERE id = ?", new Object[] { mId });
                        }
                        catch (MessagingException e)
                        {
                            throw new WrappedException(e);
                        }
                        return null;
                    }
                });
            }
            catch (WrappedException e)
            {
                throw (MessagingException) e.getCause();
            }
        }

        private void updateFolderCountsOnFlag(Flag flag, boolean set)
        {
            /*
             * Update the unread count on the folder.
             */
            try
            {
                LocalFolder folder = (LocalFolder)mFolder;
                if (flag == Flag.DELETED || flag == Flag.X_DESTROYED)
                {
                    if (!isSet(Flag.SEEN))
                    {
                        folder.setUnreadMessageCount(folder.getUnreadMessageCount() + ( set ? -1:1) );
                    }
                    if (isSet(Flag.FLAGGED))
                    {
                        folder.setFlaggedMessageCount(folder.getFlaggedMessageCount() + (set ? -1 : 1));
                    }
                }


                if ( !isSet(Flag.DELETED) )
                {

                    if ( flag == Flag.SEEN )
                    {
                        if (set != isSet(Flag.SEEN))
                        {
                            folder.setUnreadMessageCount(folder.getUnreadMessageCount() + ( set ? -1: 1) );
                        }
                    }

                    if ( flag == Flag.FLAGGED )
                    {
                        folder.setFlaggedMessageCount(folder.getFlaggedMessageCount() + (set ?  1 : -1));
                    }
                }
            }
            catch (MessagingException me)
            {
                Log.e(K9.LOG_TAG, "Unable to update LocalStore unread message count",
                      me);
                throw new RuntimeException(me);
            }
        }

        private void loadHeaders() throws UnavailableStorageException
        {
            ArrayList<LocalMessage> messages = new ArrayList<LocalMessage>();
            messages.add(this);
            mHeadersLoaded = true; // set true before calling populate headers to stop recursion
            ((LocalFolder) mFolder).populateHeaders(messages);

        }

        @Override
        public void addHeader(String name, String value) throws UnavailableStorageException
        {
            if (!mHeadersLoaded)
                loadHeaders();
            super.addHeader(name, value);
        }

        @Override
        public void setHeader(String name, String value) throws UnavailableStorageException
        {
            if (!mHeadersLoaded)
                loadHeaders();
            super.setHeader(name, value);
        }

        @Override
        public String[] getHeader(String name) throws UnavailableStorageException
        {
            if (!mHeadersLoaded)
                loadHeaders();
            return super.getHeader(name);
        }

        @Override
        public void removeHeader(String name) throws UnavailableStorageException
        {
            if (!mHeadersLoaded)
                loadHeaders();
            super.removeHeader(name);
        }

        @Override
        public Set<String> getHeaderNames() throws UnavailableStorageException
        {
            if (!mHeadersLoaded)
                loadHeaders();
            return super.getHeaderNames();
        }
    }

    public static class LocalAttachmentBodyPart extends MimeBodyPart
    {
        private long mAttachmentId = -1;

        public LocalAttachmentBodyPart(Body body, long attachmentId) throws MessagingException
        {
            super(body);
            mAttachmentId = attachmentId;
        }

        /**
         * Returns the local attachment id of this body, or -1 if it is not stored.
         * @return
         */
        public long getAttachmentId()
        {
            return mAttachmentId;
        }

        public void setAttachmentId(long attachmentId)
        {
            mAttachmentId = attachmentId;
        }

        @Override
        public String toString()
        {
            return "" + mAttachmentId;
        }
    }

    public static class LocalAttachmentBody implements Body
    {
        private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
        private Application mApplication;
        private Uri mUri;

        public LocalAttachmentBody(Uri uri, Application application)
        {
            mApplication = application;
            mUri = uri;
        }

        public InputStream getInputStream() throws MessagingException
        {
            try
            {
                return mApplication.getContentResolver().openInputStream(mUri);
            }
            catch (FileNotFoundException fnfe)
            {
                /*
                 * Since it's completely normal for us to try to serve up attachments that
                 * have been blown away, we just return an empty stream.
                 */
                return new ByteArrayInputStream(EMPTY_BYTE_ARRAY);
            }
        }

        public void writeTo(OutputStream out) throws IOException, MessagingException
        {
            InputStream in = getInputStream();
            Base64OutputStream base64Out = new Base64OutputStream(out);
            IOUtils.copy(in, base64Out);
            base64Out.close();
        }

        public Uri getContentUri()
        {
            return mUri;
        }
    }
}
